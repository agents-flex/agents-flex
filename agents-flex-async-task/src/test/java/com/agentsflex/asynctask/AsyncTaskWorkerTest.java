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
import com.agentsflex.asynctask.support.AsyncTaskTestSupport;

import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 验证查询轮询、退避重试、终态、超时、取消和租约冲突下的 Worker 行为。 */
public class AsyncTaskWorkerTest {
    @Test
    public void shouldPassQueryParamsAndContextAndPersistSuccess() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicReference<TaskQueryContext> context = new AtomicReference<>();
        AtomicReference<TaskQueryParams> params = new AtomicReference<>();
        AsyncTask task = createTask(store, 60_000);
        task.setQueryCount(4);
        task.setConsecutiveErrors(2);
        task.getQueryParams().putProviderParam("type", "batch");
        task = store.save(task, task.getVersion());

        AsyncTaskHandler<String> handler = handler((p, c) -> {
            params.set(p);
            context.set(c);
            TaskQueryResult result = new TaskQueryResult();
            result.setStatus(AsyncTaskStatus.SUCCEEDED);
            result.setProviderStatus("DONE");
            result.setResult("result");
            return result;
        });
        worker(store, handler, policy(100, 3)).queryDueTasks(1);

        AsyncTask saved = store.load(task.getId());
        assertEquals("batch", params.get().getProviderParams().get("type"));
        assertEquals(task.getId(), context.get().getTaskId());
        assertEquals(4, context.get().getQueryCount());
        assertEquals(2, context.get().getConsecutiveErrors());
        assertEquals(AsyncTaskStatus.SUCCEEDED, saved.getStatus());
        assertEquals("DONE", saved.getProviderStatus());
        assertEquals("result", saved.getResult());
        assertEquals(5, saved.getQueryCount());
        assertEquals(0, saved.getConsecutiveErrors());
        assertNull(saved.getLeaseOwner());
    }

    @Test
    public void shouldScheduleRunningResultAndReplaceQueryParams() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask task = createTask(store, 60_000);
        AsyncTaskHandler<String> handler = handler((p, c) -> {
            TaskQueryResult result = new TaskQueryResult();
            result.setStatus(AsyncTaskStatus.RUNNING);
            result.setNextQueryParams(new TaskQueryParams("next-id"));
            return result;
        });
        worker(store, handler, policy(10_000, 3)).queryDueTasks(1);
        AsyncTask saved = store.load(task.getId());
        assertEquals(AsyncTaskStatus.RUNNING, saved.getStatus());
        assertEquals("next-id", saved.getQueryParams().getExternalTaskId());
        assertTrue(saved.getNextQueryAt() > task.getNextQueryAt());
        assertTrue(saved.getNextQueryAt() <= saved.getDeadlineAt());
        assertEquals(0, worker(store, handler, policy(1, 3)).queryDueTasks(1));
    }

    @Test
    public void shouldRetryQueryExceptionsThenFail() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask task = createTask(store, 60_000);
        AsyncTaskHandler<String> handler = handler((p, c) -> { throw new IllegalStateException("temporary"); });
        AsyncTaskWorker worker = worker(store, handler, policy(1, 1));
        worker.queryDueTasks(1);
        AsyncTask retried = store.load(task.getId());
        assertEquals(AsyncTaskStatus.SUBMITTED, retried.getStatus());
        assertEquals(1, retried.getConsecutiveErrors());
        assertEquals("temporary", retried.getErrorMessage());

        retried.setNextQueryAt(0);
        store.save(retried, retried.getVersion());
        worker.queryDueTasks(1);
        AsyncTask failed = store.load(task.getId());
        assertEquals(AsyncTaskStatus.FAILED, failed.getStatus());
        assertEquals(2, failed.getConsecutiveErrors());
    }

    @Test
    public void shouldTreatInvalidHandlerResultsAsRetryableErrors() {
        QueryOperation[] invalid = new QueryOperation[] {
            (p, c) -> null,
            (p, c) -> new TaskQueryResult(),
            (p, c) -> result(AsyncTaskStatus.SUBMITTING)
        };
        for (QueryOperation operation : invalid) {
            InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
            AsyncTask task = createTask(store, 60_000);
            worker(store, handler(operation), policy(1, 0)).queryDueTasks(1);
            assertEquals(AsyncTaskStatus.FAILED, store.load(task.getId()).getStatus());
        }
    }

    @Test
    public void shouldTimeoutWithoutCallingProvider() throws Exception {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicInteger calls = new AtomicInteger();
        AsyncTask task = createTask(store, 1);
        Thread.sleep(5);
        worker(store, handler((p, c) -> { calls.incrementAndGet(); return result(AsyncTaskStatus.RUNNING); }),
            policy(1, 1)).queryDueTasks(1);
        assertEquals(AsyncTaskStatus.TRACKING_TIMED_OUT, store.load(task.getId()).getStatus());
        assertEquals(0, calls.get());
    }

    @Test
    public void shouldHonorCancellationRequestedDuringProviderQuery() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask task = createTask(store, 60_000);
        AsyncTaskHandler<String> handler = handler((p, c) -> {
            assertTrue(store.requestCancellation(c.getTaskId()));
            return result(AsyncTaskStatus.RUNNING);
        });
        worker(store, handler, policy(1, 3)).queryDueTasks(1);
        AsyncTask saved = store.load(task.getId());
        assertTrue(saved.isCancellationRequested());
        assertEquals(AsyncTaskStatus.CANCELED, saved.getStatus());
    }

    @Test
    public void shouldStartOnlyOneSchedulerAndCloseIt() throws Exception {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        createTask(store, 60_000);
        CountDownLatch queried = new CountDownLatch(1);
        AsyncTaskWorker worker = worker(store, handler((p, c) -> {
            queried.countDown();
            return result(AsyncTaskStatus.SUCCEEDED);
        }), policy(1, 1));
        worker.start(1, 1);
        worker.start(1, 1);
        assertTrue(worker.isRunning());
        assertTrue(queried.await(2, TimeUnit.SECONDS));
        worker.close();
        assertFalse(worker.isRunning());
        expect(IllegalStateException.class, () -> worker.queryDueTasks(1));
        expect(IllegalStateException.class, () -> worker.start(1, 1));
    }

    @Test
    public void shouldValidateWorkerArguments() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry();
        AsyncTaskRetryPolicy policy = policy(1, 1);
        expect(IllegalArgumentException.class, () -> new AsyncTaskWorker(null, store, registry, policy, 1));
        expect(IllegalArgumentException.class, () -> new AsyncTaskWorker("w", null, registry, policy, 1));
        expect(IllegalArgumentException.class, () -> new AsyncTaskWorker("w", store, registry, policy, 0));
        AsyncTaskWorker worker = new AsyncTaskWorker("w", store, registry, policy, 1);
        expect(IllegalArgumentException.class, () -> worker.queryDueTasks(0));
        expect(IllegalArgumentException.class, () -> worker.start(0, 1));
        worker.close();
    }

    private AsyncTask createTask(InMemoryAsyncTaskStore store, long timeoutMillis) {
        long now = store.currentTimeMillis();
        return store.create(AsyncTaskTestSupport.task("task-" + now, AsyncTaskStatus.SUBMITTED,
            now, now + timeoutMillis));
    }

    private AsyncTaskWorker worker(InMemoryAsyncTaskStore store, AsyncTaskHandler<String> handler,
                                   AsyncTaskRetryPolicy policy) {
        return new AsyncTaskWorker("worker", store,
            new InMemoryAsyncTaskHandlerRegistry().register(handler), policy, 10_000);
    }

    private AsyncTaskRetryPolicy policy(long delay, int maxErrors) {
        return new ExponentialAsyncTaskRetryPolicy(delay, delay, Math.max(delay, 10), maxErrors);
    }

    private AsyncTaskHandler<String> handler(QueryOperation operation) {
        return new AsyncTaskHandler<String>() {
            @Override public String getKey() { return "test"; }
            @Override public Class<String> getSubmitParamsType() { return String.class; }
            @Override public TaskSubmitResult submit(String params, TaskSubmitContext context) { throw new UnsupportedOperationException(); }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                return operation.query(params, context);
            }
        };
    }

    private TaskQueryResult result(AsyncTaskStatus status) {
        TaskQueryResult result = new TaskQueryResult();
        result.setStatus(status);
        return result;
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try { runnable.run(); fail("Expected " + type.getName()); }
        catch (Throwable error) { if (!type.isInstance(error)) throw error; }
    }

    private interface QueryOperation {
        TaskQueryResult query(TaskQueryParams params, TaskQueryContext context);
    }
}
