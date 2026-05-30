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
package com.agentsflex.tool.commons.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 自适应评分引擎（Adaptive Score Engine）
 *
 * <p>
 * 用于根据 Provider 的历史表现动态调整其优先级。
 * </p>
 *
 * <h3>评分模型</h3>
 *
 * <pre>
 * finalScore = baseScore × (0.5 + successRate)
 * </pre>
 *
 * 其中：
 *
 * <ul>
 *     <li>baseScore：Provider 静态评分</li>
 *     <li>successRate：历史成功率</li>
 * </ul>
 *
 * 示例：
 *
 * <pre>
 * baseScore = 100
 * successRate = 90%
 *
 * finalScore = 100 × (0.5 + 0.9)
 *            = 140
 * </pre>
 *
 * 因此成功率越高，
 * Provider 越容易被优先选择。
 *
 * <h3>设计目标</h3>
 *
 * <ul>
 *     <li>减少硬编码优先级</li>
 *     <li>自动学习最佳 Provider</li>
 *     <li>支持运行时动态调整</li>
 * </ul>
 */
public class AdaptiveScoreEngine {

    private final Map<String, ProviderMetrics> metricsMap = new ConcurrentHashMap<>();

    /**
     * 计算 Provider 最终评分。
     *
     * <p>
     * 在静态评分基础上，
     * 根据历史成功率进行动态加权。
     * </p>
     *
     * @param providerName Provider 名称
     * @param baseScore Provider 基础评分
     * @return 动态评分结果
     */
    public int score(String providerName, int baseScore) {
        ProviderMetrics m = metricsMap.computeIfAbsent(providerName, k -> new ProviderMetrics());
        double successRate = m.successRate();

        // 动态调整
        return (int) (baseScore * (0.5 + successRate));
    }

    public void recordSuccess(String provider) {
        metricsMap.computeIfAbsent(provider, k -> new ProviderMetrics())
            .recordSuccess();
    }

    public void recordFail(String provider) {
        metricsMap.computeIfAbsent(provider, k -> new ProviderMetrics())
            .recordFail();
    }
}
