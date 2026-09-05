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

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Wiki {

    private static final Set<String> RESERVED_FRONT_MATTER_KEYS = new HashSet<>();

    static {
        RESERVED_FRONT_MATTER_KEYS.add("path");
        RESERVED_FRONT_MATTER_KEYS.add("title");
        RESERVED_FRONT_MATTER_KEYS.add("summary");
    }

    private String path;
    private String title;
    private String summary;
    private String content;

    private List<Wiki> children;

    private Map<String, Object> frontMatter;

    public Wiki() {
    }

    public Wiki(String path, String title) {
        this.path = path;
        this.title = title;
    }

    public Wiki(String path, String title, String summary) {
        this.path = path;
        this.title = title;
        this.summary = summary;
    }

    public Wiki(String path, String title, String summary, Map<String, Object> frontMatter) {
        this.path = path;
        this.title = title;
        this.summary = summary;
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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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

    public void addFrontMatter(String key, Object value) {
        if (this.frontMatter == null) {
            this.frontMatter = new LinkedHashMap<>();
        }
        this.frontMatter.put(key, value);
    }

    public List<Wiki> getChildren() {
        return children;
    }

    public void setChildren(List<Wiki> children) {
        this.children = children;
    }

    public void addChild(Wiki child) {
        if (this.children == null) {
            this.children = new java.util.ArrayList<>();
        }
        this.children.add(child);
    }

    public void addChildren(List<Wiki> children) {
        if (this.children == null) {
            this.children = new java.util.ArrayList<>();
        }
        this.children.addAll(children);
    }

    public String toXml() {
        StringBuilder xml = new StringBuilder("<wiki>\n");
        appendXmlElement(xml, "path", path, "");
        appendXmlElement(xml, "title", title, "");
        appendXmlElement(xml, "summary", summary, "");

        if (this.frontMatter != null && !this.frontMatter.isEmpty()) {
            xml.append("<front_matter>\n");
            for (Map.Entry<String, Object> entry : this.frontMatter.entrySet()) {
                xml.append("  <item key=\"")
                    .append(escapeXml(entry.getKey()))
                    .append("\">")
                    .append(escapeXml(entry.getValue()))
                    .append("</item>\n");
            }
            xml.append("</front_matter>\n");
        }
        xml.append("</wiki>");
        return xml.toString();
    }

    public String toMarkdown() {
        StringBuilder markdown = new StringBuilder("---\n");
        if (StringUtil.hasText(this.path)) {
            markdown.append("path: ").append(toYamlValue(this.path)).append("\n");
        }
        if (StringUtil.hasText(this.title)) {
            markdown.append("title: ").append(toYamlValue(this.title)).append("\n");
        }
        if (StringUtil.hasText(this.summary)) {
            markdown.append("summary: ").append(toYamlValue(this.summary)).append("\n");
        }
        if (this.frontMatter != null && !this.frontMatter.isEmpty()) {
            for (Map.Entry<String, Object> entry : this.frontMatter.entrySet()) {
                if (entry.getKey() == null || RESERVED_FRONT_MATTER_KEYS.contains(entry.getKey())) {
                    continue;
                }
                markdown.append(toYamlKey(entry.getKey()))
                    .append(": ")
                    .append(toYamlValue(entry.getValue()))
                    .append("\n");
            }
        }
        markdown.append("---\n\n");
        if (this.content != null) {
            markdown.append(this.content);
        }

        if (this.children != null && !this.children.isEmpty()) {
            markdown.append("\n\n## Children Wikis:\n");
            markdown.append(WikiTool.buildWikisXml(this.children));
        }

        return markdown.toString();
    }

    private static void appendXmlElement(StringBuilder xml, String name, Object value, String indent) {
        xml.append(indent).append('<').append(name).append('>')
            .append(escapeXml(value))
            .append("</").append(name).append(">\n");
    }

    private static String escapeXml(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&apos;");
                    break;
                default:
                    escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static String toYamlKey(String key) {
        if (key != null && key.matches("[A-Za-z_][A-Za-z0-9_-]*")
            && !"null".equals(key)
            && !"true".equalsIgnoreCase(key)
            && !"false".equalsIgnoreCase(key)) {
            return key;
        }
        return yamlString(key);
    }

    private static String toYamlValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                if (!first) {
                    result.append(", ");
                }
                result.append(yamlString(String.valueOf(entry.getKey())))
                    .append(": ")
                    .append(toYamlValue(entry.getValue()));
                first = false;
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    result.append(", ");
                }
                result.append(toYamlValue(item));
                first = false;
            }
            return result.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(toYamlValue(Array.get(value, i)));
            }
            return result.append(']').toString();
        }
        return yamlString(String.valueOf(value));
    }

    private static String yamlString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
            }
        }
        return escaped.append('"').toString();
    }
}
