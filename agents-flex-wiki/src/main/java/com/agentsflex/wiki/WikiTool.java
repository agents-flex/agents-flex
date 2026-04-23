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
package com.agentsflex.wiki;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class WikiTool {


    private static final String TOOL_DESCRIPTION_TEMPLATE =
        "Access a hierarchical wiki knowledge system with progressive and recursive disclosure\n" +
            "\n" +
            "<wiki_instructions>\n" +
            "This tool provides access to a hierarchical Wiki knowledge system.\n" +
            "\n" +
            "Core concepts:\n" +
            "- Each Wiki is a node in a knowledge tree identified by a path\n" +
            "- A Wiki may have child Wikis (sub-nodes)\n" +
            "- Child Wikis are NOT embedded directly; they must be retrieved via new tool calls\n" +
            "\n" +
            "Navigation model:\n" +
            "- <available_wikis> represents the current node's accessible sub-wikis\n" +
            "- Each entry in <available_wikis> is a navigable child node\n" +
            "- To access a child wiki, call this tool again with its path\n" +
            "\n" +
            "Progressive + recursive disclosure strategy:\n" +
            "1. Start from current Wiki (path result)\n" +
            "2. Inspect title, description, frontMatter\n" +
            "3. Use available_wikis to detect possible subtopics\n" +
            "4. Decide whether to:\n" +
            "   - Answer directly from current wiki\n" +
            "   - OR navigate into one or more child wikis (recursive expansion)\n" +
            "\n" +
            "Important rules:\n" +
            "- Treat Wiki as a navigable knowledge tree, not a single document\n" +
            "- Always consider whether a child wiki may contain more precise information\n" +
            "- Only call child wiki when needed (avoid over-navigation)\n" +
            "- Do NOT assume missing content exists in current node\n" +
            "</wiki_instructions>\n" +
            "\n" +
            "<available_wikis>\n" +
            "%s\n" +
            "</available_wikis>";


    public static Builder builder() {
        return new Builder();
    }

    public static String buildWikisXml(List<Wiki> wikis) {
        String wikisXml = wikis.stream().map(Wiki::toXml).collect(Collectors.joining("\n"));
        return String.format("<available_wikis>\n%s\n</available_wikis>", wikisXml);
    }

    public static class Builder {

        private final List<Wiki> wikis = new ArrayList<>();


        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;
        private WikiProvider wikiProvider;

        protected Builder() {

        }

        public Builder toolDescriptionTemplate(String template) {
            this.toolDescriptionTemplate = template;
            return this;
        }

        public Builder addWiki(Wiki wiki) {
            this.wikis.add(wiki);
            return this;
        }

        public Builder addWikis(List<Wiki> wikis) {
            this.wikis.addAll(wikis);
            return this;
        }

        public Builder wikiProvider(WikiProvider wikiProvider) {
            this.wikiProvider = wikiProvider;
            return this;
        }


        public Tool build() {
            String wikisXml = this.wikis.stream().map(Wiki::toXml).collect(Collectors.joining("\n"));
            return Tool.builder()
                .name("get_wiki_content")
                .description(String.format(this.toolDescriptionTemplate, wikisXml))
                .addParameter(
                    Parameter.builder()
                        .name("path")
                        .type("string")
                        .required(true)
                        .description("The wiki path. ").build()
                )
                .function(stringStringMap -> {
                    String path = (String) stringStringMap.get("path");
                    Wiki wiki = wikiProvider.getWiki(path);
                    if (wiki != null) {
                        return wiki.toMarkdown();
                    }
                    return "Wiki not found: " + path;
                }).build();
        }
    }
}
