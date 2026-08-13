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

import java.util.HashMap;
import java.util.Map;

/**
 * OCR 模型的基础实现。
 *
 * <p>该类统一管理配置、默认轮询参数和输入校验。具体供应商实现负责把
 * {@link OcrRequest} 转换为供应商协议，并把响应转换回 {@link OcrResponse}。</p>
 *
 * @param <T> OCR 供应商配置类型
 */
public abstract class BaseOcrModel<T extends BaseOcrConfig> implements OcrModel {
    /**
     * 当前模型不可变的配置对象。
     */
    protected final T config;

    /**
     * 创建 OCR 模型。
     *
     * @param config 非空的供应商配置
     */
    protected BaseOcrModel(T config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    /**
     * 返回当前供应商配置。
     */
    public T getConfig() {
        return config;
    }

    /**
     * 使用配置中的超时时间和查询间隔提交任务并等待终态。
     *
     * @param request OCR 请求
     * @return 最终结果、提交错误或超时结果
     */
    public OcrResponse recognizeAndWait(OcrRequest request) {
        return recognizeAndWait(request, config.getTimeoutMillis(), config.getPollIntervalMillis());
    }

    /**
     * 校验所有供应商共享的输入约束。
     *
     * <p>URL 与本地文件必须且只能提供一个。本方法返回错误响应而不是抛异常，
     * 使参数错误与供应商请求错误都能通过统一的 {@link OcrResponse} 处理。</p>
     *
     * @return 校验失败时返回错误响应，校验通过时返回 {@code null}
     */
    protected OcrResponse validateRequest(OcrRequest request) {
        if (request == null) return OcrResponse.error("request must not be null");
        boolean hasUrl = request.getFileUrl() != null && !request.getFileUrl().trim().isEmpty();
        boolean hasFile = request.getFile() != null;
        // 使用异或约束输入来源，避免供应商收到含义不明确的双重输入。
        if (hasUrl == hasFile) return OcrResponse.error("exactly one of fileUrl or file must be provided");
        if (hasFile && (!request.getFile().isFile() || !request.getFile().canRead())) {
            return OcrResponse.error("file must be a readable regular file");
        }
        return null;
    }

    /**
     * 保存供应商原始响应中可被 {@link com.agentsflex.core.util.Metadata} 接受的字段。
     *
     * <p>Fastjson 的 {@code JSONObject} 可以包含值为 {@code null} 的顶层字段，而
     * Metadata 内部使用的 ConcurrentHashMap 不允许空键或空值。真实供应商响应经常
     * 保留尚未产生结果的空字段，因此必须先过滤，否则解析阶段会抛出空指针异常。</p>
     *
     * @param response 接收元数据的统一 OCR 响应
     * @param metadata 供应商原始响应字段
     */
    protected static void setProviderMetadata(OcrResponse response, Map<String, ?> metadata) {
        if (response == null || metadata == null || metadata.isEmpty()) return;
        Map<String, Object> nonNullMetadata = new HashMap<>();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                nonNullMetadata.put(entry.getKey(), entry.getValue());
            }
        }
        response.setMetadataMap(nonNullMetadata);
    }
}
