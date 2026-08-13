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
import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 验证同步 submit、持久化 enqueue、取消、超时和供应商结果未知等 Manager 契约。 */
public class AsyncTaskManagerTest {
    @Test
    public void shouldPersistSuccessfulSubmissionAndContext() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicReference<TaskSubmitContext> seen = new AtomicReference<>();
        AsyncTaskHandler<String> handler = handler((params, context) -> {
            seen.set(context);
            TaskSubmitResult result = new TaskSubmitResult();
            result.setStatus(AsyncTaskStatus.SUBMITTED);
            result.setQueryParams(new TaskQueryParams("provider-id"));
            return result;
        });
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenant", "t1");
        AsyncTask task = manager(store, handler).submit("manager", "request", 60_000, metadata);

        assertEquals(AsyncTaskStatus.SUBMITTED, task.getStatus());
        assertEquals("provider-id", task.getQueryParams().getExternalTaskId());
        assertEquals("t1", task.getMetadata().get("tenant"));
        assertEquals(task.getId(), seen.get().getTaskId());
        assertEquals(task.getId(), seen.get().getIdempotencyKey());
        assertTrue(task.getDeadlineAt() > task.getCreatedAt());
        assertEquals(task.getCreatedAt(), task.getNextQueryAt());
        assertEquals(1, task.getVersion());
    }

    @Test
    public void shouldPersistImmediateTerminalResultWithoutQueryParams() {
        OpaqueResult value = new OpaqueResult("done");
        AsyncTaskHandler<String> handler = handler((params, context) -> {
            TaskSubmitResult result = new TaskSubmitResult();
            result.setStatus(AsyncTaskStatus.SUCCEEDED);
            result.setResult(value);
            return result;
        });
        AsyncTask task = manager(new InMemoryAsyncTaskStore(), handler).submit("manager", "request", 1000);
        assertEquals(AsyncTaskStatus.SUCCEEDED, task.getStatus());
        assertSame(value, task.getResult());
        assertNull(task.getQueryParams());
    }

    @Test
    public void shouldPersistSubmitUnknownForHandlerFailureOrInvalidResult() {
        TaskSubmitter[] submitters = new TaskSubmitter[] {
            (p, c) -> { throw new IllegalStateException("network"); },
            (p, c) -> null,
            (p, c) -> new TaskSubmitResult(),
            (p, c) -> result(AsyncTaskStatus.SUBMITTED, null),
            (p, c) -> result(AsyncTaskStatus.SUBMITTED, new TaskQueryParams(" ")),
            (p, c) -> result(AsyncTaskStatus.SUBMITTING, null)
        };
        for (TaskSubmitter submitter : submitters) {
            InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
            AsyncTask task = manager(store, handler(submitter)).submit("manager", "request", 1000);
            assertEquals(AsyncTaskStatus.SUBMIT_UNKNOWN, task.getStatus());
            assertNotNull(task.getErrorMessage());
            assertSame(task.getStatus(), store.load(task.getId()).getStatus());
        }
    }

    @Test
    public void shouldRejectInvalidManagerInputBeforeCreatingTask() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskManager manager = manager(store, handler((p, c) -> result(AsyncTaskStatus.SUCCEEDED, null)));
        expect(IllegalArgumentException.class, () -> manager.submit("manager", "x", 0));
        expect(IllegalArgumentException.class, () -> manager.submit("manager", 42, 100));
        expect(IllegalArgumentException.class, () -> manager.submit("manager", null, 100));
        expect(IllegalStateException.class, () -> manager.submit("missing", "x", 100));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskManager(null, new InMemoryAsyncTaskHandlerRegistry()));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskManager(store, null));
    }

    @Test
    public void shouldSaturateTrackingDeadlineOnOverflow() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask task = manager(store,
            handler((p, c) -> result(AsyncTaskStatus.SUCCEEDED, null)))
            .submit("manager", "request", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, task.getDeadlineAt());
    }

    @Test
    public void shouldDelegateLookupAndCancellation() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskManager manager = manager(store,
            handler((p, c) -> result(AsyncTaskStatus.SUBMITTED, new TaskQueryParams("id"))));
        AsyncTask task = manager.submit("manager", "request", 1000);
        assertEquals(task.getId(), manager.get(task.getId()).getId());
        assertTrue(manager.cancel(task.getId()));
        assertFalse(manager.cancel(task.getId()));
        assertFalse(manager.cancel("missing"));
    }

    private AsyncTaskManager manager(InMemoryAsyncTaskStore store, AsyncTaskHandler<String> handler) {
        return new AsyncTaskManager(store, new InMemoryAsyncTaskHandlerRegistry().register(handler));
    }

    private AsyncTaskHandler<String> handler(TaskSubmitter submitter) {
        return new AsyncTaskHandler<String>() {
            @Override public String getKey() { return "manager"; }
            @Override public Class<String> getSubmitParamsType() { return String.class; }
            @Override public TaskSubmitResult submit(String params, TaskSubmitContext context) {
                return submitter.submit(params, context);
            }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private TaskSubmitResult result(AsyncTaskStatus status, TaskQueryParams params) {
        TaskSubmitResult result = new TaskSubmitResult();
        result.setStatus(status);
        result.setQueryParams(params);
        return result;
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try { runnable.run(); fail("Expected " + type.getName()); }
        catch (Throwable error) { if (!type.isInstance(error)) throw error; }
    }

    private interface TaskSubmitter {
        TaskSubmitResult submit(String params, TaskSubmitContext context);
    }

    private static final class OpaqueResult {
        private final String value;
        private OpaqueResult(String value) { this.value = value; }
    }
}
