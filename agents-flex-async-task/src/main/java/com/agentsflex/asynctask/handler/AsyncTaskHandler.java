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
package com.agentsflex.asynctask.handler;

import com.agentsflex.asynctask.*;


/**
 * 将供应商异步 API 适配为统一的“提交 + 查询”生命周期。
 *
 * <p>{@link #submit(Object, TaskSubmitContext)} 只负责向供应商创建任务；
 * {@link #query(TaskQueryParams, TaskQueryContext)} 根据持久化查询参数获取一次最新状态。
 * Handler 不负责循环、重试、超时或持久化，这些职责由 Worker 和 Store 统一完成。</p>
 */
public interface AsyncTaskHandler<P> {
    /**
     * Handler 唯一键，例如 {@code ocr:gitee}，用于注册和恢复任务。
     *
     * @return 在同一个注册表内稳定且唯一的非空键
     */
    String getKey();

    /**
     * 提交参数的运行时类型，用于在调用供应商前做明确校验。
     *
     * @return {@link #submit(Object, TaskSubmitContext)} 第一个参数所接受的具体类型
     */
    Class<P> getSubmitParamsType();

    /**
     * 创建一次供应商任务。
     *
     * <p>实现应把 {@link TaskSubmitContext#getIdempotencyKey()} 传给支持幂等键的供应商。
     * 返回可查询状态时必须提供有效的 {@link TaskQueryParams}；同步完成时可以直接返回终态。</p>
     *
     * @param params  已通过 {@link #getSubmitParamsType()} 校验的业务提交参数
     * @param context 本次提交的任务 id、幂等键、Store 时间和只读 metadata
     * @return 标准化提交结果，不能为 {@code null}，且必须包含状态
     * @throws RuntimeException 网络、鉴权或供应商调用失败；框架会记录为提交结果未知
     */
    TaskSubmitResult submit(P params, TaskSubmitContext context);

    /**
     * 查询一次供应商任务；本方法只执行一次查询，不应自行循环或休眠。
     *
     * @param params  上次提交或查询产生的可持久化供应商查询参数
     * @param context 查询次数、连续错误次数、截止时间和 metadata 等运行上下文
     * @return 本次查询的标准化结果；可通过 nextQueryParams 更新下一轮查询参数
     * @throws RuntimeException 可重试或不可重试的查询异常，由 RetryPolicy 决定后续动作
     */
    TaskQueryResult query(TaskQueryParams params, TaskQueryContext context);
}
