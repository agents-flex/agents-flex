package com.agentsflex.core.model.router.chat;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 已通过 Router 健康状态和标签过滤的聊天模型候选集合。
 *
 * <p>选择函数只能从这里返回节点，因此不会绕过熔断、节点状态或请求标签约束。</p>
 */
public final class ChatModelCandidates {
    private final List<ModelEndpoint<ChatModel>> endpoints;

    ChatModelCandidates(List<ModelEndpoint<ChatModel>> endpoints) {
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
    }

    /**
     * @return 当前全部健康候选节点，保持 Router 原有顺序。
     */
    public List<ModelEndpoint<ChatModel>> all() {
        return endpoints;
    }

    /**
     * 返回候选节点流，兼容需要按任意业务属性筛选的选择函数。
     * 推荐固定节点选择使用 {@link #named(String...)}，复杂动态规则使用此方法。
     */
    public Stream<ModelEndpoint<ChatModel>> stream() {
        return endpoints.stream();
    }

    /**
     * 按稳定 endpointId 返回候选节点，参数顺序决定返回顺序。
     */
    public List<ModelEndpoint<ChatModel>> named(String... endpointIds) {
        if (endpointIds == null || endpointIds.length == 0) return Collections.emptyList();
        Set<String> requested = new HashSet<>(Arrays.asList(endpointIds));
        List<ModelEndpoint<ChatModel>> result = new ArrayList<>();
        for (String id : endpointIds) {
            for (ModelEndpoint<ChatModel> endpoint : endpoints) {
                if (id != null && id.equals(endpoint.getEndpointId()) && requested.remove(id)) {
                    result.add(endpoint);
                    break;
                }
            }
        }
        return result;
    }
}
