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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 验证 Worker 定时启动、停止、幂等关闭和参数校验等运行时行为。 */
public class AsyncTaskRuntimeTest {

    @Test
    public void shouldSubmitAndQueryUntilSuccess() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicInteger queries = new AtomicInteger();
        AsyncTaskHandler<String> handler = handler(queries);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        AsyncTaskManager manager = new AsyncTaskManager(store, registry);

        AsyncTask submitted = manager.submit("test", "request", 60_000);
        assertEquals(AsyncTaskStatus.SUBMITTED, submitted.getStatus());
        assertEquals("external-1", submitted.getQueryParams().getExternalTaskId());

        AsyncTaskWorker worker = new AsyncTaskWorker("worker-1", store, registry,
            new ExponentialAsyncTaskRetryPolicy(10_000, 100, 1_000, 3), 5_000);
        assertEquals(1, worker.queryDueTasks(10));

        AsyncTask running = manager.get(submitted.getId());
        assertEquals(AsyncTaskStatus.RUNNING, running.getStatus());
        assertEquals(1, running.getQueryCount());
        assertEquals("external-2", running.getQueryParams().getExternalTaskId());

        running.setNextQueryAt(0);
        store.save(running, running.getVersion());
        assertEquals(1, worker.queryDueTasks(10));

        AsyncTask completed = manager.get(submitted.getId());
        assertEquals(AsyncTaskStatus.SUCCEEDED, completed.getStatus());
        assertEquals("done", completed.getResult());
        assertEquals(2, completed.getQueryCount());
        assertEquals(0, worker.queryDueTasks(10));
    }

    @Test
    public void shouldPersistSubmittingBeforeCallingHandler() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry();
        registry.register(new AsyncTaskHandler<String>() {
            @Override public String getKey() { return "failing"; }
            @Override public Class<String> getSubmitParamsType() { return String.class; }
            @Override public TaskSubmitResult submit(String params, TaskSubmitContext context) {
                AsyncTask visible = store.load(context.getTaskId());
                assertNotNull(visible);
                assertEquals(AsyncTaskStatus.SUBMITTING, visible.getStatus());
                throw new IllegalStateException("connection lost");
            }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                throw new UnsupportedOperationException();
            }
        });

        AsyncTask task = new AsyncTaskManager(store, registry).submit("failing", "request", 60_000);
        assertEquals(AsyncTaskStatus.SUBMIT_UNKNOWN, task.getStatus());
        assertEquals("connection lost", task.getErrorMessage());
    }

    @Test
    public void shouldClaimTaskOnlyOnceWhileLeaseIsActive() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask task = queryableTask("task-1", store.currentTimeMillis());
        store.create(task);

        List<AsyncTask> first = store.claimDueTasks("worker-a", store.currentTimeMillis(), 60_000, 1);
        List<AsyncTask> second = store.claimDueTasks("worker-b", store.currentTimeMillis(), 60_000, 1);

        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
        assertNotNull(first.get(0).getLeaseId());
    }

    @Test
    public void shouldCancelBeforeQueryingProvider() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicInteger queries = new AtomicInteger();
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler(queries));
        AsyncTask task = new AsyncTaskManager(store, registry).submit("test", "request", 60_000);
        assertTrue(store.requestCancellation(task.getId()));

        AsyncTaskWorker worker = new AsyncTaskWorker("worker", store, registry,
            new ExponentialAsyncTaskRetryPolicy(100, 100, 1_000, 3), 5_000);
        worker.queryDueTasks(1);

        assertEquals(AsyncTaskStatus.CANCELED, store.load(task.getId()).getStatus());
        assertEquals(0, queries.get());
    }

    private AsyncTaskHandler<String> handler(AtomicInteger queries) {
        return new AsyncTaskHandler<String>() {
            @Override public String getKey() { return "test"; }
            @Override public Class<String> getSubmitParamsType() { return String.class; }
            @Override public TaskSubmitResult submit(String params, TaskSubmitContext context) {
                assertEquals("request", params);
                TaskSubmitResult result = new TaskSubmitResult();
                result.setStatus(AsyncTaskStatus.SUBMITTED);
                result.setQueryParams(new TaskQueryParams("external-1"));
                return result;
            }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                TaskQueryResult result = new TaskQueryResult();
                if (queries.incrementAndGet() == 1) {
                    result.setStatus(AsyncTaskStatus.RUNNING);
                    result.setNextQueryParams(new TaskQueryParams("external-2"));
                } else {
                    assertEquals("external-2", params.getExternalTaskId());
                    result.setStatus(AsyncTaskStatus.SUCCEEDED);
                    result.setResult("done");
                }
                return result;
            }
        };
    }

    private AsyncTask queryableTask(String id, long now) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setHandlerKey("test");
        task.setStatus(AsyncTaskStatus.SUBMITTED);
        task.setQueryParams(new TaskQueryParams("external"));
        task.setNextQueryAt(now);
        task.setDeadlineAt(now + 60_000);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }
}
