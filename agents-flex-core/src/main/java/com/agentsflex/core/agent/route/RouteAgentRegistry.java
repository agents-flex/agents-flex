package com.agentsflex.core.agent.route;



import java.util.HashMap;
import java.util.Map;

/**
 * Agent 注册中心，用于管理所有可用的 ReActAgent 工厂。
 */
public class RouteAgentRegistry {

    public static final String DEFAULT_AGENT_NAME = "default";

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
