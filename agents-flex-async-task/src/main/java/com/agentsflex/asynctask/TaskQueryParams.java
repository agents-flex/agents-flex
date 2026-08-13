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

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 查询供应商任务所需、且必须随任务持久化的参数。
 *
 * <p>externalTaskId 覆盖大多数 API；providerParams 用于补充区域、项目、账号路由、版本号等
 * 供应商特有字段。运行次数、截止时间和 metadata 属于 TaskQueryContext，不应重复放在这里。</p>
 */
public class TaskQueryParams implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 供应商返回的任务 id、请求 id 或批次 id，是下一轮查询的主标识。
     */
    private String externalTaskId;
    /**
     * 供应商特有的附加查询参数；值必须能够被所配置的 Store 序列化器处理。
     */
    private Map<String, Object> providerParams;

    /**
     * 供序列化框架使用的无参构造器。
     */
    public TaskQueryParams() {
    }

    /**
     * 使用供应商任务主标识创建查询参数。
     */
    public TaskQueryParams(String externalTaskId) {
        this.externalTaskId = externalTaskId;
    }

    /**
     * 返回 Map 已防御性复制的查询参数副本。
     */
    public TaskQueryParams copy() {
        TaskQueryParams copy = new TaskQueryParams(externalTaskId);
        copy.setProviderParams(providerParams);
        return copy;
    }

    public String getExternalTaskId() {
        return externalTaskId;
    }

    public void setExternalTaskId(String externalTaskId) {
        this.externalTaskId = externalTaskId;
    }

    /**
     * 返回不可修改的供应商附加参数视图，未设置时返回空 Map。
     */
    public Map<String, Object> getProviderParams() {
        return providerParams == null ? Collections.emptyMap() : Collections.unmodifiableMap(providerParams);
    }

    /**
     * 防御性复制供应商附加参数，调用方后续修改原 Map 不影响本对象。
     */
    public void setProviderParams(Map<String, Object> providerParams) {
        this.providerParams = providerParams == null ? null : new HashMap<>(providerParams);
    }

    /**
     * 添加一个供应商附加查询参数；首次写入时延迟创建内部 Map。
     */
    public void putProviderParam(String key, Object value) {
        if (providerParams == null) providerParams = new HashMap<>();
        providerParams.put(key, value);
    }
}
