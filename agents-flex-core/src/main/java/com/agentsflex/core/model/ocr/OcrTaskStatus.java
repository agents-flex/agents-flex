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

/**
 * 跨供应商统一的 OCR 任务状态。
 *
 * <p>不同供应商可能使用 waiting、processing、done 等不同状态值，具体实现应将其
 * 映射为本枚举，使调用方无需了解供应商协议。</p>
 */
public enum OcrTaskStatus {
    /**
     * 供应商未返回状态，或返回了当前实现尚不认识的状态。
     */
    UNKNOWN,
    /**
     * 请求已被供应商接受，但尚未获得更精确的排队状态。
     */
    SUBMITTED,
    /**
     * 任务正在供应商队列中等待执行。
     */
    QUEUED,
    /**
     * 任务正在解析或转换。
     */
    RUNNING,
    /**
     * 任务成功完成，结果可以读取。
     */
    SUCCEEDED,
    /**
     * 任务执行失败，不应继续自动查询。
     */
    FAILED,
    /**
     * 任务已被调用方或供应商取消。
     */
    CANCELED,
    /**
     * 本地等待超过期限；供应商侧任务不一定已经停止。
     */
    TIMED_OUT;

    /**
     * 判断是否已经进入无需继续查询的终态。
     *
     * @return 成功、失败、取消或超时时返回 {@code true}
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED || this == TIMED_OUT;
    }
}
