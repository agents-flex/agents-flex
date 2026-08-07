/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.Prompt;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为当前模型请求生成包含最近一次 ToolSearch 结果的隔离 Prompt 快照。
 */
final class ToolSearchPromptResolver {

    private ToolSearchPromptResolver() {
    }

    /**
     * 普通 ChatModel 场景：从消息链中恢复每个 ToolSearchTool 最近一次完成的搜索结果。
     */
    static Prompt resolve(Prompt source) {
        // Agent Middleware 或前一个拦截器已经完成解析时，保留其请求级状态。
        if (source instanceof ResolvedPrompt) return source;
        if (source == null || source.getTools() == null) return source;
        List<Message> messages = source.getMessages();
        List<SearchState> states = new ArrayList<>();
        for (Tool tool : source.getTools()) {
            if (tool instanceof ToolSearchTool) {
                ToolSearchTool searchTool = (ToolSearchTool) tool;
                states.add(new SearchState(searchTool,
                    latestResultNames(messages, searchTool)));
            }
        }
        return states.isEmpty() ? source : resolve(source, states);
    }

    /**
     * Agent 场景：使用 Turn metadata 中已经持久化的激活名称。
     */
    static Prompt resolve(Prompt source, ToolSearchTool searchTool, List<String> activeNames) {
        if (source == null) return null;
        return resolve(source, Collections.singletonList(
            new SearchState(searchTool, activeNames)));
    }

    private static Prompt resolve(Prompt source, List<SearchState> states) {
        Map<String, Tool> visible = new LinkedHashMap<>();
        if (source.getTools() != null) {
            for (Tool tool : source.getTools()) {
                if (tool == null) continue;
                if (!isSearchCatalogTool(tool, states)) visible.put(tool.getName(), tool);
            }
        }
        for (SearchState state : states) {
            visible.put(state.searchTool.getName(), state.searchTool);
            for (String name : state.activeNames) {
                Tool tool = state.searchTool.getManager().resolve(name);
                if (tool != null) visible.putIfAbsent(name, tool);
            }
        }
        return new ResolvedPrompt(source, new ArrayList<>(visible.values()));
    }

    private static boolean isSearchCatalogTool(Tool tool, List<SearchState> states) {
        if (tool instanceof ToolSearchTool) return false;
        if (tool.getName() == null) return false;
        for (SearchState state : states) {
            if (state.searchTool.getManager().resolve(tool.getName()) == tool) return true;
        }
        return false;
    }

    private static List<String> latestResultNames(List<Message> messages,
                                                  ToolSearchTool searchTool) {
        if (messages == null || messages.isEmpty()) return Collections.emptyList();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!(message instanceof AiMessage)) continue;
            List<ToolCall> calls = ((AiMessage) message).getToolCalls();
            if (calls == null) continue;
            for (int j = calls.size() - 1; j >= 0; j--) {
                ToolCall call = calls.get(j);
                if (call == null || !searchTool.getName().equals(call.getName())) continue;
                String callId = call.getId() == null || call.getId().trim().isEmpty()
                    ? call.getName() : call.getId();
                return parseResult(messages, i + 1, callId, searchTool.getManager());
            }
        }
        return Collections.emptyList();
    }

    private static List<String> parseResult(List<Message> messages, int fromIndex,
                                            String callId, ToolSearchManager manager) {
        for (int i = fromIndex; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof ToolMessage)) continue;
            ToolMessage toolMessage = (ToolMessage) message;
            if (!callId.equals(toolMessage.getToolCallId())) continue;
            try {
                List<String> parsed = JSON.parseArray(toolMessage.getContent(), String.class);
                if (parsed == null) return Collections.emptyList();
                List<String> names = new ArrayList<>();
                for (String name : parsed) {
                    if (name != null && manager.resolve(name) != null && !names.contains(name)) {
                        names.add(name);
                    }
                }
                return names;
            } catch (RuntimeException ignored) {
                // 失败或非标准 ToolMessage 不应沿用更早的搜索结果。
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private static final class SearchState {
        private final ToolSearchTool searchTool;
        private final List<String> activeNames;

        private SearchState(ToolSearchTool searchTool, List<String> activeNames) {
            this.searchTool = searchTool;
            this.activeNames = activeNames == null ? Collections.emptyList() : activeNames;
        }
    }

    private static final class ResolvedPrompt extends Prompt {
        private final List<Message> messages;

        private ResolvedPrompt(Prompt source, List<Tool> tools) {
            List<Message> sourceMessages = source.getMessages();
            this.messages = sourceMessages == null
                ? Collections.emptyList() : new ArrayList<>(sourceMessages);
            setTools(tools);
            setToolGroups(source.getToolGroups());
            setToolChoice(source.getToolChoice());
            putMetadata(source.getMetadataMap());
        }

        @Override
        public List<Message> getMessages() {
            return new ArrayList<>(messages);
        }
    }
}
