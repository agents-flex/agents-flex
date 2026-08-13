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

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一的 OCR 提交或查询响应。
 *
 * <p>该对象同时表示异步任务状态、内联识别内容、外部结果资源以及错误信息。
 * 供应商原始响应可放入继承的 {@link Metadata} 中，既保留统一访问方式，也允许
 * 调用方在必要时读取尚未标准化的字段。</p>
 */
public class OcrResponse extends Metadata {
    /**
     * 供应商任务编号，用于后续查询。
     */
    private String taskId;
    /**
     * 统一任务状态；默认 UNKNOWN，避免空状态导致终态判断异常。
     */
    private OcrTaskStatus status = OcrTaskStatus.UNKNOWN;
    /**
     * 供应商直接返回的纯文本。
     */
    private String text;
    /**
     * 供应商直接返回的 Markdown 内容。
     */
    private String markdown;
    /**
     * 供应商返回的可下载结果资源。
     */
    private List<OcrResource> resources;
    /**
     * 是否发生参数、网络、供应商或任务执行错误。
     */
    private boolean error;
    /**
     * 供应商错误码或本地错误分类。
     */
    private String errorCode;
    /**
     * 可读错误说明。
     */
    private String errorMessage;

    /**
     * 创建不带错误码的失败响应。
     */
    public static OcrResponse error(String message) {
        return error(null, message);
    }

    /**
     * 创建标准失败响应，并将状态固定为 {@link OcrTaskStatus#FAILED}。
     *
     * @param code    可为空的错误码
     * @param message 错误说明
     */
    public static OcrResponse error(String code, String message) {
        OcrResponse response = new OcrResponse();
        response.status = OcrTaskStatus.FAILED;
        response.error = true;
        response.errorCode = code;
        response.errorMessage = message;
        return response;
    }

    /**
     * 返回当前状态是否为终态。
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 添加一个非内联的结果资源。
     */
    public void addResource(String type, String url) {
        if (resources == null) resources = new ArrayList<>();
        resources.add(new OcrResource(type, url));
    }

    /**
     * 返回不可修改的资源列表；没有资源时返回空列表。
     */
    public List<OcrResource> getResources() {
        return resources == null ? Collections.emptyList() : Collections.unmodifiableList(resources);
    }

    /**
     * 设置结果资源列表。
     */
    public void setResources(List<OcrResource> resources) {
        this.resources = resources;
    }

    /**
     * 返回供应商任务编号。
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * 设置供应商任务编号。
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * 返回统一任务状态。
     */
    public OcrTaskStatus getStatus() {
        return status;
    }

    /**
     * 设置任务状态；传入空值时规范化为 {@link OcrTaskStatus#UNKNOWN}。
     */
    public void setStatus(OcrTaskStatus status) {
        this.status = status == null ? OcrTaskStatus.UNKNOWN : status;
    }

    /**
     * 返回内联纯文本。
     */
    public String getText() {
        return text;
    }

    /**
     * 设置内联纯文本。
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * 返回内联 Markdown。
     */
    public String getMarkdown() {
        return markdown;
    }

    /**
     * 设置内联 Markdown。
     */
    public void setMarkdown(String markdown) {
        this.markdown = markdown;
    }

    /**
     * 返回响应是否表示错误。
     */
    public boolean isError() {
        return error;
    }

    /**
     * 设置错误标记。
     */
    public void setError(boolean error) {
        this.error = error;
    }

    /**
     * 返回错误码。
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * 设置错误码。
     */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /**
     * 返回错误说明。
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 设置错误说明。
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
