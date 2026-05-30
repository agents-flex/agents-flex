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

/**
 * Provider 候选对象。
 *
 * <p>
 * 用于封装 Router 排序过程中产生的候选项。
 * </p>
 *
 * <p>
 * 每个候选项包含：
 * </p>
 *
 * <ul>
 *     <li>Provider 实例</li>
 *     <li>最终评分</li>
 * </ul>
 */
public class ProviderCandidate {

    final WebReaderProvider provider;
    final int score;

    public ProviderCandidate(WebReaderProvider provider, int score) {
        this.provider = provider;
        this.score = score;
    }
}
