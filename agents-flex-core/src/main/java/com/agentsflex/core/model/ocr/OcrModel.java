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
 * OCR 能力的统一接口。
 *
 * <p>接口同时暴露“提交识别任务”和“查询任务结果”两个原子操作，以兼容文档解析
 * 常见的异步协议。调用方既可以自行持久化任务编号并调度查询，也可以使用
 * {@link #recognizeAndWait(OcrRequest, long, long)} 在当前线程等待终态。</p>
 */
public interface OcrModel {
    /**
     * 提交 OCR 识别任务。
     *
     * @param request 文件 URL 或本地文件请求
     * @return 提交结果；异步供应商通常返回任务编号和 {@link OcrTaskStatus#SUBMITTED}
     */
    OcrResponse recognize(OcrRequest request);

    /**
     * 查询已有 OCR 任务。
     *
     * @param taskId 供应商任务编号
     * @return 当前任务状态及已产生的识别结果
     */
    OcrResponse getResult(String taskId);

    /**
     * 提交任务并阻塞等待任务进入终态。
     *
     * <p>该便捷方法适用于短任务或简单调用场景。服务端应用需要跨重启恢复、限制
     * 查询 QPS 或处理大量任务时，应使用异步任务模块调度 {@link #getResult(String)}，
     * 避免长期占用业务线程。</p>
     *
     * @param request            OCR 请求
     * @param timeoutMillis      最长等待时间，必须大于零
     * @param pollIntervalMillis 查询间隔，必须大于零
     * @return 终态响应、错误响应、空响应，或状态为 {@link OcrTaskStatus#TIMED_OUT} 的超时响应
     */
    default OcrResponse recognizeAndWait(OcrRequest request, long timeoutMillis, long pollIntervalMillis) {
        if (timeoutMillis <= 0 || pollIntervalMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis and pollIntervalMillis must be greater than 0");
        }
        OcrResponse response = recognize(request);
        // 同步完成、提交失败或实现返回空值时，不再发起无意义的结果查询。
        if (response == null || response.isError() || response.isTerminal()) return response;
        if (response.getTaskId() == null || response.getTaskId().trim().isEmpty()) {
            return OcrResponse.error("OCR provider did not return a task id");
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                // 最后一次休眠不超过剩余时间，确保总等待时间不会明显越过截止点。
                Thread.sleep(Math.min(pollIntervalMillis, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                // 恢复中断标志，让上层线程池或关闭流程能够继续感知取消信号。
                Thread.currentThread().interrupt();
                return OcrResponse.error("Interrupted while waiting for OCR task " + response.getTaskId());
            }
            response = getResult(response.getTaskId());
            if (response == null || response.isTerminal()) return response;
        }
        // 保留 taskId，调用方可在超时后继续通过异步机制追踪同一个供应商任务。
        OcrResponse timeout = OcrResponse.error("Timed out waiting for OCR task " + response.getTaskId());
        timeout.setTaskId(response.getTaskId());
        timeout.setStatus(OcrTaskStatus.TIMED_OUT);
        return timeout;
    }
}
