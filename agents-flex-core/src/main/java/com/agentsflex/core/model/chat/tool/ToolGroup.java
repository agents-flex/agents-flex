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
package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.model.chat.ChatContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A conditional collection of tools and system instructions.
 * Only matched groups are serialized into an individual chat request.
 */
public class ToolGroup {

    private final String name;
    private final String description;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final ToolGroupMatcher matcher;

    private ToolGroup(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.systemPrompt = builder.systemPrompt;
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.matcher = builder.matcher;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public ToolGroupMatcher getMatcher() {
        return matcher;
    }

    public boolean matches(ChatContext context) {
        return matcher.matches(context);
    }

    public static class Builder {
        private final String name;
        private String description;
        private String systemPrompt;
        private final List<Tool> tools = new ArrayList<>();
        private ToolGroupMatcher matcher = ToolGroupMatchers.always();

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Tool group name must not be blank");
            }
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder addTool(Tool tool) {
            if (tool != null) {
                this.tools.add(tool);
            }
            return this;
        }

        public Builder addTools(Collection<? extends Tool> tools) {
            if (tools != null) {
                for (Tool tool : tools) {
                    addTool(tool);
                }
            }
            return this;
        }

        public Builder matcher(ToolGroupMatcher matcher) {
            if (matcher == null) {
                throw new IllegalArgumentException("Tool group matcher must not be null");
            }
            this.matcher = matcher;
            return this;
        }

        public ToolGroup build() {
            return new ToolGroup(this);
        }
    }
}
