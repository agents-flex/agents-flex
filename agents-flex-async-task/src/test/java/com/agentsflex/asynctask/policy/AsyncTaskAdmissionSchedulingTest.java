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
package com.agentsflex.asynctask.policy;
import com.agentsflex.asynctask.support.AsyncTaskTestSupport;

import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 验证供应商 QPS、账号并发、租户配额、优先级、延迟提交和供应商暂停的调度能力。 */
public class AsyncTaskAdmissionSchedulingTest {
    @Test
    public void shouldEnforceProviderQpsAndResetAfterWindow() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskAdmissionPolicy policy = new InMemoryAsyncTaskAdmissionPolicy();
        policy.setProviderQps("provider", 2);
        store.create(pending("a", "provider", "account-a", "tenant-a", 0, 0));
        store.create(pending("b", "provider", "account-b", "tenant-b", 0, 0));
        store.create(pending("c", "provider", "account-c", "tenant-c", 0, 0));

        assertEquals(2, store.claimDueSubmissions("w", 1000, 100, 10, policy).size());
        assertTrue(store.claimDueSubmissions("w", 1500, 100, 10, policy).isEmpty());
        assertEquals(1, store.claimDueSubmissions("w", 2000, 100, 10, policy).size());
    }

    @Test
    public void shouldEnforceAccountConcurrencyUntilTaskTerminates() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskAdmissionPolicy policy = new InMemoryAsyncTaskAdmissionPolicy();
        policy.setAccountConcurrency("provider", "account", 1);
        store.create(pending("first", "provider", "account", "t1", 0, 0));
        store.create(pending("second", "provider", "account", "t2", 0, 0));

        long now = store.currentTimeMillis();
        AsyncTask first = store.claimDueSubmissions("w", now, 60_000, 10, policy).get(0);
        assertTrue(store.claimDueSubmissions("w2", now, 60_000, 10, policy).isEmpty());
        first.setStatus(AsyncTaskStatus.SUCCEEDED);
        store.save(first, first.getVersion());
        store.releaseLease(first.getId(), "w", first.getLeaseId());
        assertEquals(1, store.claimDueSubmissions("w2", now + 1, 60_000, 10, policy).size());
    }

    @Test
    public void shouldEnforceTenantActiveTaskQuotaAcrossProvidersAndAccounts() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskAdmissionPolicy policy = new InMemoryAsyncTaskAdmissionPolicy();
        policy.setTenantQuota("tenant", 1);
        store.create(pending("a", "provider-a", "account-a", "tenant", 0, 0));
        store.create(pending("b", "provider-b", "account-b", "tenant", 0, 0));
        assertEquals(1, store.claimDueSubmissions("w", 10, 100, 10, policy).size());
        assertTrue(store.claimDueSubmissions("w2", 10, 100, 10, policy).isEmpty());
    }

    @Test
    public void shouldClaimHigherPriorityFirstWithStableTimeOrdering() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask low = pending("low", "p", "a1", "t1", 1, 0);
        AsyncTask highLater = pending("high-later", "p", "a2", "t2", 10, 5);
        AsyncTask highEarly = pending("high-early", "p", "a3", "t3", 10, 0);
        store.create(low);
        store.create(highLater);
        store.create(highEarly);
        List<AsyncTask> claimed = store.claimDueSubmissions("w", 10, 100, 3,
            (task, all, now) -> true);
        assertEquals("high-early", claimed.get(0).getId());
        assertEquals("high-later", claimed.get(1).getId());
        assertEquals("low", claimed.get(2).getId());
    }

    @Test
    public void shouldRespectDelayedSubmission() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(pending("delayed", "p", "a", "t", 0, 100));
        assertTrue(store.claimDueSubmissions("w", 99, 100, 1,
            (task, all, now) -> true).isEmpty());
        assertEquals(1, store.claimDueSubmissions("w", 100, 100, 1,
            (task, all, now) -> true).size());
    }

    @Test
    public void shouldPauseAndResumeOneProviderWithoutBlockingOthers() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        InMemoryAsyncTaskAdmissionPolicy policy = new InMemoryAsyncTaskAdmissionPolicy();
        policy.pauseProvider("paused");
        assertTrue(policy.isProviderPaused("paused"));
        store.create(pending("paused-task", "paused", "a", "t1", 0, 0));
        store.create(pending("active-task", "active", "a", "t2", 0, 0));
        List<AsyncTask> claimed = store.claimDueSubmissions("w", 10, 100, 10, policy);
        assertEquals(1, claimed.size());
        assertEquals("active-task", claimed.get(0).getId());
        policy.resumeProvider("paused");
        assertFalse(policy.isProviderPaused("paused"));
        assertEquals(1, store.claimDueSubmissions("w", 11, 100, 10, policy).size());
    }

    @Test
    public void shouldEnqueueAndSubmitPersistedParamsThroughWorker() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicInteger submissions = new AtomicInteger();
        List<String> values = new ArrayList<>();
        AsyncTaskHandler<PersistedRequest> handler = handler(submissions, values, false);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        AsyncTaskManager manager = new AsyncTaskManager(store, registry);
        AsyncTaskOptions options = options("provider", "account", "tenant", 7, 0);
        AsyncTask queued = manager.submit(new PersistedRequest("request"), 60_000, options);
        assertEquals(AsyncTaskStatus.PENDING_SUBMIT, queued.getStatus());
        assertEquals("provider", queued.getProviderKey());
        assertEquals(7, queued.getPriority());

        AsyncTaskWorker worker = new AsyncTaskWorker("worker", store, registry,
            new ExponentialAsyncTaskRetryPolicy(100, 100, 1000, 3),
            new InMemoryAsyncTaskAdmissionPolicy(), 10_000);
        assertEquals(1, worker.submitDueTasks(10));
        AsyncTask submitted = store.load(queued.getId());
        assertEquals(AsyncTaskStatus.SUBMITTED, submitted.getStatus());
        assertEquals("external-request", submitted.getQueryParams().getExternalTaskId());
        assertNull(submitted.getSubmitParams());
        assertEquals(1, submissions.get());
        assertEquals("request", values.get(0));
    }

    @Test
    public void shouldPersistSubmitUnknownAndReleaseLeaseOnBackgroundFailure() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskHandler<PersistedRequest> handler = handler(new AtomicInteger(), new ArrayList<>(), true);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        AsyncTask queued = new AsyncTaskManager(store, registry).submit(
            new PersistedRequest("request"), 60_000, options("p", "a", "t", 0, 0));
        AsyncTaskWorker worker = new AsyncTaskWorker("worker", store, registry,
            new ExponentialAsyncTaskRetryPolicy(1, 1, 10, 1), 10_000);
        worker.submitDueTasks(1);
        AsyncTask failed = store.load(queued.getId());
        assertEquals(AsyncTaskStatus.SUBMIT_UNKNOWN, failed.getStatus());
        assertEquals("submit failed", failed.getErrorMessage());
        assertNull(failed.getLeaseOwner());
    }

    @Test
    public void shouldCancelOrTimeoutBeforeCallingSubmitHandler() throws Exception {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AtomicInteger submissions = new AtomicInteger();
        AsyncTaskHandler<PersistedRequest> handler = handler(submissions, new ArrayList<>(), false);
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        AsyncTaskManager manager = new AsyncTaskManager(store, registry);
        AsyncTask canceled = manager.submit(new PersistedRequest("cancel"), 60_000,
            options("p", "a", "t", 0, 0));
        assertTrue(manager.cancel(canceled.getId()));
        AsyncTask expired = manager.submit(new PersistedRequest("expired"), 1,
            options("p", "b", "t2", 0, 0));
        Thread.sleep(5);

        AsyncTaskWorker worker = new AsyncTaskWorker("worker", store, registry,
            new ExponentialAsyncTaskRetryPolicy(1, 1, 10, 1), 10_000);
        assertEquals(2, worker.submitDueTasks(10));
        assertEquals(AsyncTaskStatus.CANCELED, store.load(canceled.getId()).getStatus());
        assertEquals(AsyncTaskStatus.TRACKING_TIMED_OUT, store.load(expired.getId()).getStatus());
        assertEquals(0, submissions.get());
    }

    @Test
    public void shouldRejectCorruptPersistedParamsAndInvalidSubmitResults() {
        SubmitBehavior[] behaviors = new SubmitBehavior[] {
            (p, c) -> null,
            (p, c) -> new TaskSubmitResult(),
            (p, c) -> submitResult(AsyncTaskStatus.SUBMITTING, null),
            (p, c) -> submitResult(AsyncTaskStatus.SUBMITTED, null),
            (p, c) -> submitResult(AsyncTaskStatus.RUNNING, new TaskQueryParams(" "))
        };
        for (SubmitBehavior behavior : behaviors) {
            InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
            AsyncTaskHandler<PersistedRequest> handler = behaviorHandler(behavior);
            InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
            AsyncTask task = new AsyncTaskManager(store, registry).submit(
                new PersistedRequest("x"), 60_000, options("p", "a", "t", 0, 0));
            new AsyncTaskWorker("w", store, registry,
                new ExponentialAsyncTaskRetryPolicy(1, 1, 10, 1), 10_000).submitDueTasks(1);
            assertEquals(AsyncTaskStatus.SUBMIT_UNKNOWN, store.load(task.getId()).getStatus());
        }

        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskHandler<PersistedRequest> handler = behaviorHandler(
            (p, c) -> submitResult(AsyncTaskStatus.SUBMITTED, new TaskQueryParams("id")));
        InMemoryAsyncTaskHandlerRegistry registry = new InMemoryAsyncTaskHandlerRegistry().register(handler);
        AsyncTask task = new AsyncTaskManager(store, registry).submit(
            new PersistedRequest("x"), 60_000, options("p", "a", "t", 0, 0));
        task.setSubmitParams("wrong-type");
        store.save(task, task.getVersion());
        new AsyncTaskWorker("w", store, registry,
            new ExponentialAsyncTaskRetryPolicy(1, 1, 10, 1), 10_000).submitDueTasks(1);
        assertEquals(AsyncTaskStatus.SUBMIT_UNKNOWN, store.load(task.getId()).getStatus());
    }

    @Test
    public void shouldValidateAdmissionConfigurationAndEnqueueOptions() {
        InMemoryAsyncTaskAdmissionPolicy policy = new InMemoryAsyncTaskAdmissionPolicy();
        expect(() -> policy.setProviderQps(null, 1));
        expect(() -> policy.setProviderQps("p", 0));
        expect(() -> policy.setAccountConcurrency("p", "a", 0));
        expect(() -> policy.setTenantQuota("t", 0));
        expect(() -> policy.pauseProvider(" "));
        AsyncTaskOptions options = new AsyncTaskOptions();
        expect(() -> options.setDelayMillis(-1));
        options.setProviderKey("p");
        options.setHandlerKey("handler");
        options.setAccountId("a");
        options.setTenantId("t");
        options.setPriority(9);
        options.setDelayMillis(10);
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("key", "value");
        options.setMetadata(metadata);
        metadata.put("key", "changed");
        assertEquals("p", options.getProviderKey());
        assertEquals("handler", options.getHandlerKey());
        assertEquals("a", options.getAccountId());
        assertEquals("t", options.getTenantId());
        assertEquals(9, options.getPriority());
        assertEquals(10, options.getDelayMillis());
        assertEquals("value", options.getMetadata().get("key"));
        try {
            options.getMetadata().put("another", "value");
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // metadata 对外只读，避免任务创建前被调用方绕过 setMetadata 修改。
        }
        options.setMetadata(null);
        assertTrue(options.getMetadata().isEmpty());

        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTaskHandler<PersistedRequest> handler = handler(new AtomicInteger(), new ArrayList<>(), false);
        AsyncTaskManager manager = new AsyncTaskManager(store,
            new InMemoryAsyncTaskHandlerRegistry().register(handler));
        expect(() -> manager.submit(null, 1000, null));
        expect(() -> manager.submit(new PersistedRequest("x"), 0, null));
        expect(() -> store.claimDueSubmissions("w", 1, 1, 1, null));
    }

    private AsyncTask pending(String id, String provider, String account, String tenant,
                              int priority, long scheduledAt) {
        AsyncTask task = AsyncTaskTestSupport.task(id, AsyncTaskStatus.PENDING_SUBMIT, 0, Long.MAX_VALUE);
        task.setProviderKey(provider);
        task.setAccountId(account);
        task.setTenantId(tenant);
        task.setPriority(priority);
        task.setScheduledSubmitAt(scheduledAt);
        task.setSubmitParams(new PersistedRequest(id));
        return task;
    }

    private AsyncTaskOptions options(String provider, String account, String tenant,
                                                int priority, long delay) {
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setProviderKey(provider);
        options.setAccountId(account);
        options.setTenantId(tenant);
        options.setPriority(priority);
        options.setDelayMillis(delay);
        return options;
    }

    private AsyncTaskHandler<PersistedRequest> handler(AtomicInteger submissions,
                                                        List<String> values, boolean fail) {
        return new AsyncTaskHandler<PersistedRequest>() {
            @Override public String getKey() { return "handler"; }
            @Override public Class<PersistedRequest> getSubmitParamsType() { return PersistedRequest.class; }
            @Override public TaskSubmitResult submit(PersistedRequest params, TaskSubmitContext context) {
                submissions.incrementAndGet();
                values.add(params.value);
                if (fail) throw new IllegalStateException("submit failed");
                TaskSubmitResult result = new TaskSubmitResult();
                result.setStatus(AsyncTaskStatus.SUBMITTED);
                result.setQueryParams(new TaskQueryParams("external-" + params.value));
                return result;
            }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                TaskQueryResult result = new TaskQueryResult();
                result.setStatus(AsyncTaskStatus.RUNNING);
                return result;
            }
        };
    }

    private AsyncTaskHandler<PersistedRequest> behaviorHandler(SubmitBehavior behavior) {
        return new AsyncTaskHandler<PersistedRequest>() {
            @Override public String getKey() { return "handler"; }
            @Override public Class<PersistedRequest> getSubmitParamsType() { return PersistedRequest.class; }
            @Override public TaskSubmitResult submit(PersistedRequest params, TaskSubmitContext context) {
                return behavior.submit(params, context);
            }
            @Override public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static TaskSubmitResult submitResult(AsyncTaskStatus status, TaskQueryParams params) {
        TaskSubmitResult result = new TaskSubmitResult();
        result.setStatus(status);
        result.setQueryParams(params);
        return result;
    }

    private void expect(Runnable runnable) {
        try { runnable.run(); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }

    private static final class PersistedRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String value;
        private PersistedRequest(String value) { this.value = value; }
    }

    private interface SubmitBehavior {
        TaskSubmitResult submit(PersistedRequest params, TaskSubmitContext context);
    }
}
