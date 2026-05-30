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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provider 运行指标统计。
 *
 * <p>
 * 用于记录某个 Provider 的调用结果，
 * 为自适应评分引擎提供数据支撑。
 * </p>
 *
 * <h3>统计内容</h3>
 *
 * <ul>
 *     <li>成功次数</li>
 *     <li>失败次数</li>
 *     <li>成功率</li>
 * </ul>
 *
 * <p>
 * 当前采用累计统计模型。
 * 后续可扩展为：
 * </p>
 *
 * <ul>
 *     <li>滑动窗口统计</li>
 *     <li>指数衰减统计</li>
 *     <li>最近 N 次调用统计</li>
 * </ul>
 */
public class ProviderMetrics {

    AtomicInteger success = new AtomicInteger(0);
    AtomicInteger fail = new AtomicInteger(0);

    /**
     * 获取成功率。
     *
     * <p>
     * 当尚无历史记录时，
     * 返回默认值 0.5，
     * 表示 Provider 处于中立状态。
     * </p>
     *
     * @return 成功率（0~1）
     */
    double successRate() {
        int s = success.get();
        int f = fail.get();
        int total = s + f;
        return total == 0 ? 0.5 : (double) s / total;
    }

    void recordSuccess() {
        success.incrementAndGet();
    }

    void recordFail() {
        fail.incrementAndGet();
    }
}
