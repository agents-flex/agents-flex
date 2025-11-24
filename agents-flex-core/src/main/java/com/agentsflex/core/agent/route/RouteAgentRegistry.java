/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.agent.route;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 注册中心，用于管理所有可用的 ReActAgent 工厂。
 */
public class RouteAgentRegistry {

    private final Map<String, RouteAgentFactory> agentFactories = new HashMap<>();
    private final Map<String, String> agentDescriptions = new HashMap<>();
    private final Map<String, String> keywordToAgent = new HashMap<>();

    /**
     * 注册 Agent，并可选绑定关键字（用于快速匹配）
     */
    public void register(String name, String description, RouteAgentFactory factory, String... keywords) {
        agentFactories.put(name, factory);
        agentDescriptions.put(name, description);

        for (String kw : keywords) {
            if (kw != null && !kw.trim().isEmpty()) {
                keywordToAgent.put(kw.trim().toLowerCase(), name);
            }
        }
    }

    // 按关键字查找 Agent
    public String findAgentByKeyword(String userQuery) {
        if (userQuery == null) return null;
        String lowerQuery = userQuery.toLowerCase();
        for (Map.Entry<String, String> entry : keywordToAgent.entrySet()) {
            if (lowerQuery.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }


    public RouteAgentFactory getAgentFactory(String name) {
        return agentFactories.get(name);
    }

    public String getAgentDescriptions() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : agentDescriptions.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString().trim();
    }
}
