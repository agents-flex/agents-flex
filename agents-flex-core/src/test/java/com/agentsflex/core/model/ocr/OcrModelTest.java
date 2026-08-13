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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * {@link OcrModel} 默认轮询流程的单元测试。
 *
 * <p>测试使用内存中的匿名模型模拟供应商状态变化，不依赖网络和真实凭证。</p>
 */
public class OcrModelTest {
    /** 验证默认等待逻辑会持续查询，直到任务从运行态进入成功终态。 */
    @Test
    public void shouldWaitUntilTaskSucceeds() {
        OcrModel model = new OcrModel() {
            private int queries;

            // 模拟供应商提交接口：只返回后续查询所需的任务编号。
            public OcrResponse recognize(OcrRequest request) {
                OcrResponse response = new OcrResponse();
                response.setTaskId("task-1");
                response.setStatus(OcrTaskStatus.SUBMITTED);
                return response;
            }

            // 第一次查询仍在运行，第二次查询返回成功，用于验证循环没有提前结束。
            public OcrResponse getResult(String taskId) {
                OcrResponse response = new OcrResponse();
                response.setTaskId(taskId);
                response.setStatus(++queries > 1 ? OcrTaskStatus.SUCCEEDED : OcrTaskStatus.RUNNING);
                return response;
            }
        };
        OcrResponse response = model.recognizeAndWait(OcrRequest.ofUrl("https://example.com/a.pdf"), 500, 1);
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
    }

    /** 验证零超时时间会在发起供应商请求前被拒绝。 */
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidPollingConfiguration() {
        OcrModel model = new OcrModel() {
            public OcrResponse recognize(OcrRequest request) {
                OcrResponse response = new OcrResponse();
                response.setTaskId("task-1");
                return response;
            }
            public OcrResponse getResult(String taskId) { return new OcrResponse(); }
        };
        model.recognizeAndWait(new OcrRequest(), 0, 1);
    }
}
