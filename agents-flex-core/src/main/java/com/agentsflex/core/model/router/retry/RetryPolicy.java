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
package com.agentsflex.core.model.router.retry;

/**
 * 重试策略。
 * <p>
 * 用于控制：
 * <p>
 * 请求失败后是否继续重试。
 */
public interface RetryPolicy {

    /**
     * 是否继续重试。
     *
     * @param retryCount 当前重试次数
     * @param throwable  异常信息
     */
    boolean shouldRetry(int retryCount, Throwable throwable);

    /**
     * 返回下一次重试前的等待时间，单位为毫秒。
     *
     * <p>默认不等待，兼容已有策略实现。返回负数时按 0 处理。</p>
     */
    default long retryDelayMillis(int retryCount, Throwable throwable) {
        return 0L;
    }

}
