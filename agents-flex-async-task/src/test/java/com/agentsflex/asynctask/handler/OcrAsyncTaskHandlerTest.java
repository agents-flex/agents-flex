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
package com.agentsflex.asynctask.handler;

import com.agentsflex.asynctask.*;
import com.agentsflex.core.model.ocr.OcrModel;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 验证 OCR 模型提交和供应商状态到统一异步任务状态的映射。
 */
public class OcrAsyncTaskHandlerTest {
    @Test
    public void shouldSubmitRequestAndCreateQueryParams() {
        AtomicReference<OcrRequest> seen = new AtomicReference<>();
        OcrResponse response = response(OcrTaskStatus.QUEUED, "ocr-1");
        OcrAsyncTaskHandler handler = new OcrAsyncTaskHandler("ocr:test", model(seen, response, null));
        assertEquals("ocr:test", handler.getKey());
        assertEquals(OcrRequest.class, handler.getSubmitParamsType());
        OcrRequest request = OcrRequest.ofUrl("https://example.com/a.pdf");
        TaskSubmitResult result = handler.submit(request, new TaskSubmitContext("task", 1, null));
        assertSame(request, seen.get());
        assertEquals(AsyncTaskStatus.RUNNING, result.getStatus());
        assertEquals("ocr-1", result.getQueryParams().getExternalTaskId());
    }

    @Test
    public void shouldMapAllOcrStatuses() {
        assertSubmitStatus(OcrTaskStatus.SUBMITTED, AsyncTaskStatus.SUBMITTED);
        assertSubmitStatus(OcrTaskStatus.QUEUED, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(OcrTaskStatus.RUNNING, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(OcrTaskStatus.UNKNOWN, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(OcrTaskStatus.TIMED_OUT, AsyncTaskStatus.RUNNING);
        assertSubmitStatus(OcrTaskStatus.SUCCEEDED, AsyncTaskStatus.SUCCEEDED);
        assertSubmitStatus(OcrTaskStatus.FAILED, AsyncTaskStatus.FAILED);
        assertSubmitStatus(OcrTaskStatus.CANCELED, AsyncTaskStatus.CANCELED);
    }

    @Test
    public void shouldMapSuccessfulAndFailedQueries() {
        OcrResponse success = response(OcrTaskStatus.SUCCEEDED, "ocr");
        success.setMarkdown("done");
        AtomicReference<String> queried = new AtomicReference<>();
        OcrAsyncTaskHandler handler = new OcrAsyncTaskHandler("ocr:test",
            model(new AtomicReference<>(), null, taskId -> {
                queried.set(taskId);
                return success;
            }));
        TaskQueryResult result = handler.query(new TaskQueryParams("ocr"), queryContext());
        assertEquals("ocr", queried.get());
        assertEquals(AsyncTaskStatus.SUCCEEDED, result.getStatus());
        assertSame(success, result.getResult());
        assertEquals("SUCCEEDED", result.getProviderStatus());

        OcrResponse failure = OcrResponse.error("E1", "failed");
        handler = new OcrAsyncTaskHandler("ocr:test",
            model(new AtomicReference<>(), null, id -> failure));
        result = handler.query(new TaskQueryParams("ocr"), queryContext());
        assertEquals(AsyncTaskStatus.FAILED, result.getStatus());
        assertNull(result.getResult());
        assertEquals("E1", result.getErrorCode());
        assertEquals("failed", result.getErrorMessage());
    }

    @Test
    public void shouldHandleNullProviderResponses() {
        OcrAsyncTaskHandler handler = new OcrAsyncTaskHandler("ocr:test",
            model(new AtomicReference<>(), null, id -> null));
        TaskSubmitResult submitted = handler.submit(new OcrRequest(), new TaskSubmitContext("task", 1, null));
        assertEquals(AsyncTaskStatus.FAILED, submitted.getStatus());
        assertNotNull(submitted.getErrorMessage());
        TaskQueryResult queried = handler.query(new TaskQueryParams("id"), queryContext());
        assertEquals(AsyncTaskStatus.FAILED, queried.getStatus());
        assertNotNull(queried.getErrorMessage());
    }

    @Test
    public void shouldAllowProviderSpecificQueryParamsAndQueryLogic() {
        OcrResponse response = response(OcrTaskStatus.SUBMITTED, "batch-1");
        AtomicReference<String> type = new AtomicReference<>();
        OcrAsyncTaskHandler handler = new OcrAsyncTaskHandler("ocr:test",
            model(new AtomicReference<>(), response, null)) {
            @Override
            protected TaskQueryParams createQueryParams(OcrResponse value, OcrRequest request) {
                TaskQueryParams params = super.createQueryParams(value, request);
                params.putProviderParam("type", "batch");
                return params;
            }

            @Override
            protected OcrResponse queryModel(TaskQueryParams params, TaskQueryContext context) {
                type.set(String.valueOf(params.getProviderParams().get("type")));
                return OcrAsyncTaskHandlerTest.response(OcrTaskStatus.SUCCEEDED, params.getExternalTaskId());
            }
        };
        TaskSubmitResult submitted = handler.submit(new OcrRequest(), new TaskSubmitContext("task", 1, null));
        handler.query(submitted.getQueryParams(), queryContext());
        assertEquals("batch", type.get());
    }

    @Test
    public void shouldValidateConstructor() {
        OcrModel model = model(new AtomicReference<>(), null, null);
        expect(() -> new OcrAsyncTaskHandler(null, model));
        expect(() -> new OcrAsyncTaskHandler(" ", model));
        expect(() -> new OcrAsyncTaskHandler("key", null));
    }

    private void assertSubmitStatus(OcrTaskStatus source, AsyncTaskStatus expected) {
        OcrResponse response = response(source, source.isTerminal() ? null : "id");
        TaskSubmitResult result = new OcrAsyncTaskHandler("ocr:test",
            model(new AtomicReference<>(), response, null))
            .submit(new OcrRequest(), new TaskSubmitContext("task", 1, null));
        assertEquals(expected, result.getStatus());
        if (source == OcrTaskStatus.SUCCEEDED) assertSame(response, result.getResult());
    }

    private static OcrResponse response(OcrTaskStatus status, String taskId) {
        OcrResponse response = new OcrResponse();
        response.setStatus(status);
        response.setTaskId(taskId);
        return response;
    }

    private OcrModel model(AtomicReference<OcrRequest> request, OcrResponse submit, Query query) {
        return new OcrModel() {
            @Override
            public OcrResponse recognize(OcrRequest value) {
                request.set(value);
                return submit;
            }

            @Override
            public OcrResponse getResult(String taskId) {
                return query == null ? null : query.get(taskId);
            }
        };
    }

    private TaskQueryContext queryContext() {
        return new TaskQueryContext("task", 0, 0, 1, 100, 2, null);
    }

    private void expect(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    private interface Query {
        OcrResponse get(String taskId);
    }
}
