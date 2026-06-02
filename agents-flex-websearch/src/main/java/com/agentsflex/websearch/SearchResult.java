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

import com.agentsflex.core.util.Metadata;

import java.util.Map;

public class SearchResult extends Metadata {

    private String title;
    private String url;
    private String description;

    private Map<String, Object> frontMatter;


    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    public void setFrontMatter(Map<String, Object> frontMatter) {
        this.frontMatter = frontMatter;
    }

    public String toMarkdown() {
        String safeUrl = escapeUrl(this.url);
        StringBuilder sb = new StringBuilder();
        if (frontMatter != null && !frontMatter.isEmpty()) {
            sb.append("---\n");
            for (Map.Entry<String, Object> entry : frontMatter.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("---\n");
        }
        sb.append("# ").append(title).append("\n\n");
        sb.append("URL: ").append(safeUrl).append("\n\n");
        sb.append(description == null ? "" : description);
        return sb.toString();
    }


    private String escapeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.replace("(", "%28")
            .replace(")", "%29")
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace(" ", "%20");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String title;
        private String url;
        private String description;
        private Map<String, Object> frontMatter;

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder frontMatter(Map<String, Object> frontMatter) {
            if (this.frontMatter == null) {
                this.frontMatter = new java.util.HashMap<>();
            }
            this.frontMatter.putAll(frontMatter);
            return this;
        }

        public Builder frontMatter(String key, Object value) {
            if (this.frontMatter == null) {
                this.frontMatter = new java.util.HashMap<>();
            }
            this.frontMatter.put(key, value);
            return this;
        }

        public SearchResult build() {
            SearchResult result = new SearchResult();
            result.setTitle(this.title);
            result.setUrl(this.url);
            result.setDescription(this.description);
            result.setFrontMatter(this.frontMatter);
            return result;
        }
    }
}
