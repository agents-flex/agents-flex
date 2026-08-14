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
 * 异步任务的调度、隔离和扩展选项。
 *
 * <p>所有任务都会先持久化再由 Worker 提交。providerKey 用于供应商 QPS 与暂停，
 * providerKey + accountId 用于账号并发，tenantId 用于租户配额；priority 越大越先提交，
 * delayMillis 控制最早允许提交的时间。</p>
 */
public class AsyncTaskOptions {
    /**
     * 强制路由到指定 Handler 的注册键。
     *
     * <p>通常无需设置：Manager 会按提交参数类型查找 Handler。仅当同一请求类型注册了多个 Handler，
     * 且本次请求必须使用某个确定实现时设置。该值优先于 Manager 的 Handler Selector；指定的 Handler
     * 不存在或参数类型不匹配时直接失败，不会静默降级到其他 Handler。</p>
     */
    private String handlerKey;
    /**
     * 供应商限流维度；为空时 Manager 使用 handlerKey。
     */
    private String providerKey;
    /**
     * 供应商账号并发维度；为空表示不做账号隔离。
     */
    private String accountId;
    /**
     * 租户配额维度；为空表示不做租户隔离。
     */
    private String tenantId;
    /**
     * 提交优先级，数值越大越先领取。
     */
    private int priority;
    /**
     * 从任务创建时刻起计算的最小提交延迟。
     */
    private long delayMillis;
    /**
     * 持久化并透传给 Handler 的业务扩展信息。
     *
     * <p>键和值必须可序列化，且不能包含文件、流或字节数组；敏感凭证应由 Handler
     * 根据账号等稳定标识从服务端配置中获取，不应直接写入 metadata。</p>
     */
    private Map<String, Object> metadata;

    public String getHandlerKey() {
        return handlerKey;
    }

    public void setHandlerKey(String handlerKey) {
        this.handlerKey = handlerKey;
    }

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
