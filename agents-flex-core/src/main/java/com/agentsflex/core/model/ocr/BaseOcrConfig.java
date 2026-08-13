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
package com.agentsflex.core.model.ocr;

import com.agentsflex.core.model.config.BaseModelConfig;

/**
 * OCR 模型的通用配置。
 *
 * <p>除继承模型供应商、服务地址、提交路径等基础配置外，本类还定义异步 OCR
 * 任务的结果查询路径、默认查询间隔和最长等待时间。供应商实现只需补充自己的
 * 默认端点和路径，即可复用 {@link OcrModel#recognizeAndWait(OcrRequest, long, long)}
 * 提供的同步等待能力。</p>
 */
public class BaseOcrConfig extends BaseModelConfig {
    /**
     * 结果查询路径，可使用 {@code {taskId}} 作为任务编号占位符。
     */
    private String queryPath;
    /**
     * 两次结果查询之间的默认等待时间，单位为毫秒。
     */
    private long pollIntervalMillis = 3_000L;
    /**
     * 同步等待 OCR 任务完成的默认超时时间，单位为毫秒。
     */
    private long timeoutMillis = 10 * 60_000L;

    /**
     * 返回结果查询路径。
     */
    public String getQueryPath() {
        return queryPath;
    }

    /**
     * 设置结果查询路径；非空路径缺少前导斜杠时会自动补齐。
     *
     * @param queryPath 供应商的结果查询路径
     */
    public void setQueryPath(String queryPath) {
        this.queryPath = queryPath != null && !queryPath.startsWith("/") ? "/" + queryPath : queryPath;
    }

    /**
     * 根据任务编号构造完整的结果查询地址。
     *
     * @param taskId 供应商返回的任务编号
     * @return 完整查询地址
     */
    public String getQueryUrl(String taskId) {
        return getEndpoint() + queryPath.replace("{taskId}", taskId);
    }

    /**
     * 返回默认查询间隔，单位为毫秒。
     */
    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    /**
     * 设置默认查询间隔，调用等待方法时该值必须大于零。
     */
    public void setPollIntervalMillis(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }

    /**
     * 返回默认等待超时时间，单位为毫秒。
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 设置默认等待超时时间，调用等待方法时该值必须大于零。
     */
    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
