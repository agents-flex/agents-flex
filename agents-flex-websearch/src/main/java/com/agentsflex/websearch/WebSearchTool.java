/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.websearch;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.core.util.StringUtil;
import org.slf4j.Logger;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class WebSearchTool {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(WebSearchTool.class);

    private final SearchProvider provider;

    private int maxResults = 10;

    public WebSearchTool(SearchProvider provider) {
        this.provider = provider;
    }

    public SearchProvider getProvider() {
        return provider;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    // @formatter:off
    @ToolDef(
        name = "web_search",
        description = "Search web content and return relevant results with optional domain filtering"
    )
    public String webSearch(
        @ToolParam(name = "query", description = "search query", required = true) String query,
        @ToolParam(name = "allowed_domains", description = "Only include search results from these domains") List<String> allowedDomains,
        @ToolParam(name = "blocked_domains", description = "Never include search results from these domains") List<String> blockedDomains
    ) {// @formatter:on

        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        request.setMaxResults(this.maxResults);
        request.setAllowedDomains(allowedDomains);
        request.setBlockedDomains(blockedDomains);

        List<SearchResult> results;

        try {
            results = provider.search(request);
        } catch (Exception e) {
            log.error("An error occurred while searching for web content: " + e.getMessage(), e);
            return "ERROR: An error occurred while searching for web content: " + e.getMessage();
        }

        if (results != null && !results.isEmpty()) {
            results = applyDomainFilter(results, allowedDomains, blockedDomains);
        }

        if (results == null || results.isEmpty()) {
            return "No search results were found.\n" +
                "\n" +
                "Original query: \n" + query +
                "\n" +
                "Possible reasons:\n" +
                "- The query may be too specific.\n" +
                "- The search provider returned no matching documents.\n" +
                "- Domain filters may have excluded all results.\n" +
                "\n" +
                "Suggested actions:\n" +
                "1. Broaden the search query.\n" +
                "2. Remove or relax domain restrictions.\n" +
                "3. Try alternative keywords.\n" +
                "4. Retry the search with different wording.";
        }

        return results.stream()
            .map(SearchResult::toMarkdown)
            .collect(Collectors.joining("\n\n-----\n\n"));
    }


    private List<SearchResult> applyDomainFilter(
        List<SearchResult> results,
        List<String> allowedDomains,
        List<String> blockedDomains) {

        if ((allowedDomains == null || allowedDomains.isEmpty())
            && (blockedDomains == null || blockedDomains.isEmpty())) {
            return results;
        }

        Set<String> includeSet = normalize(allowedDomains);
        Set<String> excludeSet = normalize(blockedDomains);

        return results.stream()
            .filter(r -> filter(r.getUrl(), includeSet, excludeSet))
            .collect(Collectors.toList());
    }

    private boolean filter(String url,
                           Set<String> include,
                           Set<String> exclude) {

        String domain = extractDomain(url);

        if (!include.isEmpty() && !match(domain, include)) {
            return false;
        }

        if (!exclude.isEmpty() && match(domain, exclude)) {
            return false;
        }

        return true;
    }

    private Set<String> normalize(List<String> domains) {

        if (domains == null || domains.isEmpty()) {
            return Collections.emptySet();
        }

        return domains.stream()
            .filter(StringUtil::hasText)
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
    }

    private boolean match(String domain, Set<String> rules) {

        for (String r : rules) {
            if (domain.equals(r) || domain.endsWith("." + r)) {
                return true;
            }
        }

        return false;
    }

    private String extractDomain(String url) {

        if (StringUtil.noText(url)) {
            return "";
        }

        try {
            String u = url.trim();

            if (!u.startsWith("http")) {
                u = "https://" + u;
            }

            URI uri = URI.create(u);
            String host = uri.getHost();

            return host == null ? u.toLowerCase() : host.toLowerCase();

        } catch (Exception e) {
            return url.toLowerCase();
        }
    }


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SearchProvider provider;
        private int maxResults = 10;

        public Builder provider(SearchProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public WebSearchTool build() {
            WebSearchTool tool = new WebSearchTool(provider);
            tool.setMaxResults(maxResults);
            return tool;
        }
    }
}
