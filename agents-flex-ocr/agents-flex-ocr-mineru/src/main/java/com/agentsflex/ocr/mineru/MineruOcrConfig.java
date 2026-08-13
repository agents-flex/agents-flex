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
package com.agentsflex.ocr.mineru;

import com.agentsflex.core.model.ocr.BaseOcrConfig;

/**
 * MinerU 文档解析服务配置。
 *
 * <p>远程 URL 使用普通单任务接口；本地文件使用“申请预签名上传地址、上传文件、
 * 查询批任务结果”的三段式接口，因此除通用路径外还包含上传和批量查询路径。</p>
 */
public class MineruOcrConfig extends BaseOcrConfig {
    /**
     * 申请预签名文件上传地址的路径。
     */
    private String uploadPath = "/api/v4/file-urls/batch";
    /**
     * 本地文件上传后对应的批任务查询路径。
     */
    private String batchQueryPath = "/api/v4/extract-results/batch/{taskId}";

    /**
     * 使用 MinerU 官方端点和 pipeline 默认模型创建配置。
     */
    public MineruOcrConfig() {
        setProvider("mineru");
        setEndpoint("https://mineru.net");
        setRequestPath("/api/v4/extract/task");
        setQueryPath("/api/v4/extract/task/{taskId}");
        setModel(MineruOcrModels.PIPELINE);
    }

    /**
     * 返回申请上传地址的路径。
     */
    public String getUploadPath() {
        return uploadPath;
    }

    /**
     * 设置上传路径；缺少前导斜杠时自动补齐。
     */
    public void setUploadPath(String uploadPath) {
        this.uploadPath = uploadPath != null && !uploadPath.startsWith("/") ? "/" + uploadPath : uploadPath;
    }

    /**
     * 返回申请上传地址的完整 URL。
     */
    public String getUploadUrl() {
        return getEndpoint() + uploadPath;
    }

    /**
     * 返回批任务查询路径。
     */
    public String getBatchQueryPath() {
        return batchQueryPath;
    }

    /**
     * 设置批任务查询路径；缺少前导斜杠时自动补齐。
     */
    public void setBatchQueryPath(String batchQueryPath) {
        this.batchQueryPath = batchQueryPath != null && !batchQueryPath.startsWith("/") ? "/" + batchQueryPath : batchQueryPath;
    }

    /**
     * 使用批任务编号构造完整查询 URL。
     */
    public String getBatchQueryUrl(String taskId) {
        return getEndpoint() + batchQueryPath.replace("{taskId}", taskId);
    }
}
