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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 持久化提交的调度和隔离维度。
 *
 * <p>providerKey 用于 QPS 与暂停；providerKey + accountId 用于账号并发；tenantId 用于租户配额；
 * priority 越大越先提交；delayMillis 控制最早提交时间。</p>
 */
public final class AsyncTaskSubmissionOptions {
    /** 供应商限流维度；为空时 Manager 使用 handlerKey。 */
    private String providerKey;
    /** 供应商账号并发维度，可为空表示不做账号隔离。 */
    private String accountId;
    /** 租户配额维度，可为空表示不做租户隔离。 */
    private String tenantId;
    /** 提交优先级，数值越大越先领取；相同优先级按计划时间和创建时间排序。 */
    private int priority;
    /** 从 enqueue 时刻起计算的最小提交延迟，必须大于等于 0。 */
    private long delayMillis;
    /** 持久化并透传给 Handler 的业务扩展信息。 */
    private Map<String, Object> metadata;

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public void setDelayMillis(long delayMillis) {
        if (delayMillis < 0) throw new IllegalArgumentException("delayMillis must not be negative");
        this.delayMillis = delayMillis;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? null : new HashMap<>(metadata);
    }
}
