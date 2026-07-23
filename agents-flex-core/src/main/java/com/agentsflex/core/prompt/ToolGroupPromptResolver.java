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
package com.agentsflex.core.prompt;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolGroup;
import com.agentsflex.core.model.chat.tool.ToolGroupMatchContext;
import com.agentsflex.core.util.MessageUtil;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves conditional tool groups into an isolated request-level prompt snapshot.
 */
public final class ToolGroupPromptResolver {

    private ToolGroupPromptResolver() {
    }

    public static Prompt resolve(Prompt prompt) {
        if (prompt == null || prompt.getToolGroups().isEmpty()) {
            return prompt;
        }

        List<Message> messages = prompt.getMessages();
        UserMessage lastUserMessage = MessageUtil.findLastUserMessage(messages);
        ToolGroupMatchContext matchContext = new ToolGroupMatchContext(prompt, messages, lastUserMessage);
        List<ToolGroup> matchedGroups = new ArrayList<>();
        for (ToolGroup toolGroup : prompt.getToolGroups()) {
            if (toolGroup.matches(matchContext)) {
                matchedGroups.add(toolGroup);
            }
        }

        return new ResolvedPrompt(prompt, messages, matchedGroups);
    }

    private static class ResolvedPrompt extends Prompt {
        private final List<Message> messages;

        private ResolvedPrompt(Prompt source, List<Message> sourceMessages, List<ToolGroup> matchedGroups) {
            this.messages = appendSystemPrompts(sourceMessages, matchedGroups);
            setTools(mergeTools(source.getTools(), matchedGroups));
            setToolChoice(source.getToolChoice());
            putMetadata(source.getMetadataMap());
        }

        @Override
        public List<Message> getMessages() {
            return new ArrayList<>(messages);
        }
    }

    private static List<Tool> mergeTools(List<Tool> tools, List<ToolGroup> matchedGroups) {
        Map<String, Tool> merged = new LinkedHashMap<>();
        if (tools != null) {
            for (Tool tool : tools) {
                addTool(merged, tool);
            }
        }
        for (ToolGroup group : matchedGroups) {
            for (Tool tool : group.getTools()) {
                addTool(merged, tool);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static void addTool(Map<String, Tool> tools, Tool tool) {
        if (tool != null && tool.getName() != null) {
            tools.put(tool.getName(), tool);
        }
    }

    private static List<Message> appendSystemPrompts(List<Message> sourceMessages,
                                                      List<ToolGroup> matchedGroups) {
        List<Message> messages = sourceMessages == null
            ? new ArrayList<>()
            : new ArrayList<>(sourceMessages);
        StringBuilder additions = new StringBuilder();
        for (ToolGroup group : matchedGroups) {
            if (StringUtil.hasText(group.getSystemPrompt())) {
                if (additions.length() > 0) {
                    additions.append("\n\n");
                }
                additions.append(group.getSystemPrompt());
            }
        }
        if (additions.length() == 0) {
            return messages;
        }

        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                SystemMessage systemMessage = ((SystemMessage) messages.get(i)).copy();
                String content = systemMessage.getContent();
                systemMessage.setContent(StringUtil.hasText(content)
                    ? content + "\n\n" + additions
                    : additions.toString());
                messages.set(i, systemMessage);
                return messages;
            }
        }

        messages.add(0, new SystemMessage(additions.toString()));
        return messages;
    }
}
