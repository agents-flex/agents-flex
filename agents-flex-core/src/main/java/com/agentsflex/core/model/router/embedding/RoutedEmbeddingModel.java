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
package com.agentsflex.core.model.router.embedding;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.model.router.balance.ModelLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.core.AbstractModelRouter;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.breaker.DefaultCircuitBreaker;
import com.agentsflex.core.model.router.balance.LeastActiveLoadBalancer;
import com.agentsflex.core.model.router.retry.DefaultRetryPolicy;
import com.agentsflex.core.model.router.retry.RetryPolicy;
import com.agentsflex.core.store.VectorData;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 面向 {@link EmbeddingModel} 的进程内模型路由器。
 *
 * <p>该类把多个嵌入模型包装成一个标准 {@code EmbeddingModel}，在调用方无感知的情况下完成
 * 标签过滤、负载均衡、失败重试、节点熔断和运行指标统计。向量检索代码只需要依赖一个模型，
 * 不需要在每次生成向量时自行判断主模型是否可用。</p>
 *
 * <p>Router 只负责选择能够处理请求的节点，不会转换不同嵌入模型的向量空间。放在同一个
 * Router 中的模型必须具有兼容的向量维度、距离度量、归一化方式和语义空间；否则同一索引中
 * 混用不同节点生成的向量会导致检索结果不可比较。</p>
 */
public class RoutedEmbeddingModel extends AbstractModelRouter<EmbeddingModel> implements EmbeddingModel {

    /**
     * 使用业务指定的 Endpoint、负载均衡器、重试策略和熔断器创建路由模型。
     *
     * @param endpoints     可参与路由的嵌入模型节点
     * @param loadBalancer  从可用节点中选择节点的策略
     * @param retryPolicy   根据异常决定是否重试的策略
     * @param circuitBreaker 控制节点健康状态的熔断器
     */
    public RoutedEmbeddingModel(
        List<ModelEndpoint<EmbeddingModel>> endpoints,
        ModelLoadBalancer<EmbeddingModel> loadBalancer,
        RetryPolicy retryPolicy,
        CircuitBreaker<EmbeddingModel> circuitBreaker) {

        super(
            endpoints,
            loadBalancer,
            retryPolicy,
            circuitBreaker
        );
    }

    /**
     * 使用默认的最少活跃请求负载均衡、三次额外重试和默认熔断器创建路由模型。
     *
     * <p>三次额外重试不包含首次请求，持续发生瞬时故障时最多尝试四次。只有具备相同向量
     * 契约的模型才适合通过此构造方法放在一起。</p>
     */
    public RoutedEmbeddingModel(List<EmbeddingModel> models) {
        super(
            models.stream().map(ModelEndpoint::new).collect(java.util.stream.Collectors.toList()),
            new LeastActiveLoadBalancer<>(),
            new DefaultRetryPolicy(3),
            new DefaultCircuitBreaker<>()
        );
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        // EmbeddingModel 的便捷方法通常会传入 DEFAULT，但直接调用本方法时仍允许 options 为 null。
        return execute(
            model -> model.embed(document, options),
            extractTags(options)
        );
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractTags(EmbeddingOptions options) {
        if (options == null) {
            return Collections.emptySet();
        }

        Object value = options.getMetadata("modelTags");

        if (value instanceof Set<?>) {
            // 只有同时满足全部标签的 Endpoint 才会进入候选集合。
            return (Set<String>) value;
        }

        return Collections.emptySet();
    }
}
