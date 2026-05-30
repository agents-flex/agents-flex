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

import java.util.*;
import java.util.function.Function;

/**
 * 自适应 WebReader 路由器（Adaptive Web Reader Router）
 * <p>
 * 用于在多个 {@link WebReaderProvider} 之间进行智能选择与自动降级。
 *
 * <h3>工作流程</h3>
 * <ol>
 *     <li>筛选当前 URL 支持的 Provider</li>
 *     <li>根据 Provider 的静态评分(score)计算基础优先级</li>
 *     <li>结合历史成功率进行动态加权</li>
 *     <li>按最终评分从高到低排序</li>
 *     <li>依次尝试读取内容</li>
 *     <li>成功立即返回</li>
 *     <li>失败自动降级到下一个 Provider</li>
 * </ol>
 *
 * <h3>示例</h3>
 * <p>
 * 当前存在：
 *
 * <pre>
 * JinaReaderProvider
 * BrowserReaderProvider
 * HttpReaderProvider
 * </pre>
 * <p>
 * 对于某个 URL：
 *
 * <pre>
 * jina      score=90
 * browser   score=80
 * http      score=60
 * </pre>
 * <p>
 * Router 会优先尝试 Jina，
 * 若失败则自动切换 Browser，
 * Browser 再失败则切换 Http。
 *
 * <h3>自适应学习</h3>
 * <p>
 * Router 会记录每个 Provider 的：
 *
 * <ul>
 *     <li>成功次数</li>
 *     <li>失败次数</li>
 *     <li>成功率</li>
 * </ul>
 * <p>
 * 历史表现优秀的 Provider 会获得更高权重，
 * 表现较差的 Provider 会自动降低优先级。
 * <p>
 * 因此系统会随着运行时间不断优化 Provider 选择策略。
 *
 * <h3>线程安全</h3>
 * <p>
 * Provider 指标统计通过
 * {@link java.util.concurrent.ConcurrentHashMap}
 * 与 {@link java.util.concurrent.atomic.AtomicInteger}
 * 实现线程安全。
 *
 * <h3>扩展方式</h3>
 * <p>
 * 新增 Provider 仅需实现：
 *
 * <pre>
 * WebReaderProvider
 * </pre>
 * <p>
 * Router 无需修改即可自动接入。
 *
 * @author Miachel yang
 */
public class AdaptiveWebReaderRouter {

    private final List<WebReaderProvider> providers;
    private final AdaptiveScoreEngine scoreEngine;

    public AdaptiveWebReaderRouter(List<WebReaderProvider> providers) {
        this.providers = providers;
        this.scoreEngine = new AdaptiveScoreEngine();
    }


    /**
     * 对当前 URL 可用的 Provider 进行排序。
     *
     * <p>排序分两步：</p>
     *
     * <ol>
     *     <li>获取 Provider 的静态评分</li>
     *     <li>结合历史成功率计算动态评分</li>
     * </ol>
     * <p>
     * 最终按照评分从高到低排序。
     *
     * @param url 目标网址
     * @return 已排序的候选 Provider 列表
     */
    private List<ProviderCandidate> rank(String url) {
        List<ProviderCandidate> list = new ArrayList<>();
        for (WebReaderProvider p : providers) {
            if (!p.supports(url)) continue;
            int baseScore = p.score(url);
            int finalScore = scoreEngine.score(p.name(), baseScore);
            list.add(new ProviderCandidate(p, finalScore));
        }

        list.sort((a, b) -> Integer.compare(b.score, a.score));
        return list;
    }


    /**
     * 读取网页内容。
     *
     * <p>
     * Router 会按照评分顺序依次尝试 Provider。
     * 当某个 Provider 成功返回内容时立即结束。
     * </p>
     *
     * <p>
     * 若当前 Provider 调用失败，
     * 会自动记录失败指标并降级到下一个 Provider。
     * </p>
     *
     * <p>
     * 所有 Provider 都失败时抛出异常。
     * </p>
     *
     * @param url 网页地址
     * @return 网页内容
     * @throws RuntimeException 所有 Provider 均失败
     */
    public String read(String url, Function<String, String> transformer) {
        List<ProviderCandidate> candidates = rank(url);
        Exception last = null;

        for (ProviderCandidate candidate : candidates) {
            try {
                String result = candidate.provider.read(url);

                if (result != null && transformer != null) {
                    result = transformer.apply(result);
                }

                if (result != null && !result.isEmpty()) {
                    scoreEngine.recordSuccess(candidate.provider.name());
                    return result;
                }

                scoreEngine.recordFail(candidate.provider.name());
            } catch (Exception e) {
                last = e;
                scoreEngine.recordFail(candidate.provider.name());
                System.out.println("[WARN] provider failed: "
                    + candidate.provider.name() + " -> " + e.getMessage());
            }
        }

        throw new RuntimeException("All providers failed", last);
    }
}
