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
package com.agentsflex.asynctask.store;

import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.AsyncTaskAdmissionPolicy;


import java.util.List;

/**
 * 异步任务持久化与多 Worker 协调契约。
 *
 * <p>save 必须按 expectedVersion 执行 CAS；领取必须生成唯一 leaseId 并递增 version；
 * 续租和释放必须同时校验 workerId 与 leaseId，避免过期 Worker 覆盖新 Worker 的结果。</p>
 */
public interface AsyncTaskStore {
    /**
     * 返回 Store 权威时钟，外部 Store 应使用服务端时间减少节点时钟漂移。
     *
     * @return Unix Epoch 毫秒时间戳；同一次调度判断应使用该时间来源
     */
    long currentTimeMillis();

    /**
     * 创建版本为 0 的任务，同 id 已存在时失败。
     *
     * @param task 待创建任务；实现必须保存防御性副本，不能持有调用方可变引用
     * @return version 被规范为 0 的已保存快照副本
     * @throws IllegalStateException id 已存在
     */
    AsyncTask create(AsyncTask task);

    /**
     * 加载最新快照，不存在返回 null。
     *
     * @param taskId 框架任务 id
     * @return 与 Store 内部状态隔离的任务副本，或 {@code null}
     */
    AsyncTask load(String taskId);

    /**
     * 按期望版本保存并返回递增版本后的快照。
     *
     * <p>实现必须保持 cancellationRequested 单调，并拒绝过期或不匹配租约的写入。</p>
     *
     * @param task            调用方修改后的任务副本
     * @param expectedVersion 调用方读取或领取时获得的版本
     * @return version 等于 expectedVersion + 1 的最新已保存快照
     * @throws AsyncTaskVersionConflictException 当前版本与期望版本不一致
     * @throws IllegalStateException             任务不存在、租约过期或 fencing token 不匹配
     */
    AsyncTask save(AsyncTask task, long expectedVersion);

    /**
     * 按优先级领取到期提交任务，并在领取过程中执行准入判断。
     *
     * <p>成功领取必须把状态改为 SUBMITTING、生成唯一 leaseId、设置 leaseUntil 并递增版本。
     * 排序规则为优先级降序、scheduledSubmitAt 升序、createdAt 升序。</p>
     *
     * @param workerId        当前 Worker 的稳定实例标识
     * @param now             来自 {@link #currentTimeMillis()} 的本轮判断时间
     * @param leaseMillis     租约长度，必须大于 0
     * @param limit           最大领取数量，必须大于 0
     * @param admissionPolicy 候选任务准入策略
     * @return 已获得租约的任务快照列表
     */
    List<AsyncTask> claimDueSubmissions(String workerId, long now, long leaseMillis, int limit,
                                        AsyncTaskAdmissionPolicy admissionPolicy);

    /**
     * 按 nextQueryAt 领取到期查询任务。
     *
     * @param workerId    当前 Worker 标识
     * @param now         Store 权威时钟时间
     * @param leaseMillis 租约长度
     * @param limit       最大领取数量
     * @return 按 nextQueryAt 升序领取的任务快照
     */
    List<AsyncTask> claimDueTasks(String workerId, long now, long leaseMillis, int limit);

    /**
     * 仅由当前租约持有者延长租约，版本号不变化。
     *
     * @param taskId     任务 id
     * @param workerId   当前租约 owner
     * @param leaseId    本次领取产生的 fencing token
     * @param now        Store 权威时钟时间，用于确认原租约仍有效
     * @param leaseUntil 新的绝对到期时间，必须晚于 now
     * @return 更新租约后的最新快照
     */
    AsyncTask renewLease(String taskId, String workerId, String leaseId, long now, long leaseUntil);

    /**
     * 仅在 owner 和 leaseId 匹配时释放租约；不匹配时保持幂等。
     *
     * @param taskId   任务 id
     * @param workerId 预期租约 owner
     * @param leaseId  预期 fencing token
     */
    void releaseLease(String taskId, String workerId, String leaseId);

    /**
     * 单调设置取消请求；终态、重复请求和不存在均返回 false。
     *
     * @param taskId 任务 id
     * @return 本次是否首次成功写入取消请求
     */
    boolean requestCancellation(String taskId);
}
