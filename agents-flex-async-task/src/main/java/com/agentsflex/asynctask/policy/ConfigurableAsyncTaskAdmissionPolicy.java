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


/**
 * 可在运行期配置常用隔离维度的准入策略。
 */
public interface ConfigurableAsyncTaskAdmissionPolicy extends AsyncTaskAdmissionPolicy {
    /**
     * 设置供应商最近 1000 毫秒滑动窗口内允许的最大提交次数。
     */
    void setProviderQps(String providerKey, int qps);

    /**
     * 设置同一供应商账号处于已提交但未终态状态的最大任务数。
     */
    void setAccountConcurrency(String providerKey, String accountId, int limit);

    /**
     * 设置租户处于已提交但未终态状态的最大任务数。
     */
    void setTenantQuota(String tenantId, int activeTaskLimit);

    /**
     * 暂停供应商的新任务提交；已提交任务仍可继续查询。
     */
    void pauseProvider(String providerKey);

    /**
     * 恢复供应商的新任务提交。
     */
    void resumeProvider(String providerKey);

    /**
     * 判断供应商当前是否暂停提交。
     */
    boolean isProviderPaused(String providerKey);
}
