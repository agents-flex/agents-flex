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

import com.agentsflex.core.util.StringUtil;

import java.util.Map;
import java.util.stream.Collectors;

public class Wiki {

    private String path;
    private String title;
    private String description;
    private String content;

    private Map<String, Object> frontMatter;

    public Wiki() {
    }

    public Wiki(String path, String title, String description) {
        this.path = path;
        this.title = title;
        this.description = description;
    }

    public Wiki(String path, String title, String description, Map<String, Object> frontMatter) {
        this.path = path;
        this.title = title;
        this.description = description;
        this.frontMatter = frontMatter;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getFrontMatter() {
        return frontMatter;
    }

    public void setFrontMatter(Map<String, Object> frontMatter) {
        this.frontMatter = frontMatter;
    }

    public String toXml() {
        String frontMatterXml = this.frontMatter == null ? "" : this.frontMatter
            .entrySet()
            .stream()
            .map(e -> String.format("  <%s>%s</%s>", e.getKey(), e.getValue(), e.getKey()))
            .collect(Collectors.joining("\n"));

        return String.format("<wiki>\n<path>%s</path>\n<title>%s</title>\n<description>%s</description>\n%s\n</wiki>", path, title, description, frontMatterXml);
    }

    public String toMarkdown() {
        StringBuilder markdown = new StringBuilder("---\n");
        if (StringUtil.hasText(this.path)) {
            markdown.append("path: ").append(this.path).append("\n");
        }
        if (StringUtil.hasText(this.title)) {
            markdown.append("title: ").append(this.title).append("\n");
        }
        if (StringUtil.hasText(this.description)) {
            markdown.append("description: ").append(this.description).append("\n");
        }
        if (this.frontMatter != null && !this.frontMatter.isEmpty()) {
            markdown.append(this.frontMatter.entrySet().stream().map(e -> String.format("%s: %s", e.getKey(), e.getValue())).collect(Collectors.joining("\n"))).append("\n");
        }
        markdown.append("---\n");
        markdown.append(this.content);
        return markdown.toString();
    }
}
