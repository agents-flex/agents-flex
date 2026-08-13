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
package com.agentsflex.asynctask.policy;

import com.agentsflex.asynctask.AsyncTask;
import com.agentsflex.asynctask.TaskQueryResult;


/**
 * 决定正常查询间隔和查询异常退避行为的策略。
 */
public interface AsyncTaskRetryPolicy {
    /**
     * 供应商正常返回可查询状态后，计算下一次查询延迟。
     *
     * @param task   已合并本次查询结果的任务快照
     * @param result Handler 本次返回的查询结果
     * @return 严格大于 0 的毫秒延迟
     */
    long nextQueryDelayMillis(AsyncTask task, TaskQueryResult result);

    /**
     * Handler 抛出异常且允许重试时，计算异常退避延迟。
     *
     * @param task  已递增 consecutiveErrors 的任务快照
     * @param error 本次查询异常
     * @return 严格大于 0 的毫秒延迟
     */
    long nextErrorDelayMillis(AsyncTask task, RuntimeException error);

    /**
     * 判断本次查询异常是否仍允许重试。
     *
     * @param task  已递增 consecutiveErrors 的任务快照
     * @param error 本次查询异常，可用于区分限流、鉴权或参数错误
     * @return {@code true} 时安排下一轮查询，否则任务转为 FAILED
     */
    boolean shouldRetry(AsyncTask task, RuntimeException error);
}
