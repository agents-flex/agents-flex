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
package com.agentsflex.asynctask;

/**
 * 持久化异步任务的框架生命周期。
 *
 * <p>PENDING_SUBMIT/SUBMITTING 属于后台提交阶段；SUBMITTED/RUNNING 可查询；
 * SUCCEEDED、FAILED、CANCELED、TRACKING_TIMED_OUT 和 SUBMIT_UNKNOWN 均为终态。</p>
 */
public enum AsyncTaskStatus {
    /**
     * 已持久化提交参数，但尚未获得准入额度，也尚未调用供应商。
     */
    PENDING_SUBMIT,
    /**
     * Worker 已领取提交租约，正在调用供应商创建任务。
     */
    SUBMITTING,
    /**
     * 供应商已经接受任务并返回查询标识，尚未确认进入实际处理阶段。
     */
    SUBMITTED,
    /**
     * 供应商明确表示任务正在排队或处理中，Worker 将继续按计划查询。
     */
    RUNNING,
    /**
     * 供应商任务成功完成，{@code result} 应保存最终业务结果。
     */
    SUCCEEDED,
    /**
     * 供应商明确失败，或查询异常超过重试上限；不会继续自动查询。
     */
    FAILED,
    /**
     * 本地收到取消请求并停止跟踪；不代表供应商一定已经取消远端任务。
     */
    CANCELED,
    /**
     * 到达本地跟踪截止时间；供应商远端任务仍可能继续运行。
     */
    TRACKING_TIMED_OUT,
    /**
     * 提交调用抛出异常，框架无法判断供应商是否已经创建任务。
     *
     * <p>该状态默认不自动重新提交，避免网络超时后在供应商侧创建重复任务；业务可结合
     * 幂等键、供应商查询接口或人工补偿决定后续动作。</p>
     */
    SUBMIT_UNKNOWN;

    /**
     * 判断任务是否已经停止被 Worker 自动调度。
     *
     * @return 成功、失败、取消、跟踪超时或提交结果未知时返回 {@code true}
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED
            || this == TRACKING_TIMED_OUT || this == SUBMIT_UNKNOWN;
    }

    /**
     * 判断当前状态是否允许调用 Handler 的查询方法。
     *
     * @return 仅 {@link #SUBMITTED} 和 {@link #RUNNING} 返回 {@code true}
     */
    public boolean isQueryable() {
        return this == SUBMITTED || this == RUNNING;
    }

    /**
     * 判断任务是否仍停留在持久化待提交队列。
     *
     * @return 仅 {@link #PENDING_SUBMIT} 返回 {@code true}
     */
    public boolean isPendingSubmission() {
        return this == PENDING_SUBMIT;
    }
}
