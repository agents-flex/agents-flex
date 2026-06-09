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
package com.agentsflex.subagent;

import com.agentsflex.core.util.Metadata;

import java.util.Map;
import java.util.stream.Collectors;

public class SubagentDefinition extends Metadata {

    private String name;
    private String description;

    private Map<String, Object> frontMatter;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String toXml() {
        String frontMatterXml = this.frontMatter == null ? ""
            : this.frontMatter
            .entrySet()
            .stream()
            .map(e -> String.format("  <%s>%s</%s>", e.getKey(), e.getValue(), e.getKey()))
            .collect(Collectors.joining("\n")) + "\n";

        return String.format("\t<task_agent>\n\t\t<name>%s</name>\n\t\t<description>%s</description>\n%s\t</task_agent>"
            , name, description, frontMatterXml);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SubagentDefinition definition = new SubagentDefinition();

        public Builder() {
        }

        public Builder name(String name) {
            definition.name = name;
            return this;
        }

        public Builder description(String description) {
            definition.description = description;
            return this;
        }

        public Builder frontMatter(Map<String, Object> frontMatter) {
            definition.frontMatter = frontMatter;
            return this;
        }

        public Builder frontMatter(String key, Object value) {
            if (definition.frontMatter == null) {
                definition.frontMatter = new java.util.HashMap<>();
            }
            definition.frontMatter.put(key, value);
            return this;
        }

        public Builder metadata(String key, Object value) {
            definition.putMetadata(key, value);
            return this;
        }

        public SubagentDefinition build() {
            return definition;
        }

    }

    @Override
    public String toString() {
        return "SubagentDefinition{" +
            "name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", frontMatter=" + frontMatter +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
