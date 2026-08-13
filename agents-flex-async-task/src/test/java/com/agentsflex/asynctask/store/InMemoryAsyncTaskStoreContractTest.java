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
package com.agentsflex.asynctask.store;
import com.agentsflex.asynctask.support.AsyncTaskTestSupport;

import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.store.*;


import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/** 验证内存 Store 的 CAS、租约 fencing、调度顺序、取消单调性和并发领取契约。 */
public class InMemoryAsyncTaskStoreContractTest {
    @Test
    public void shouldCreateLoadAndSaveDefensiveCopiesWithCas() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        AsyncTask original = AsyncTaskTestSupport.task("task", AsyncTaskStatus.SUBMITTED, 10, 100);
        AsyncTask created = store.create(original);
        assertEquals(0, created.getVersion());

        original.setStatus(AsyncTaskStatus.FAILED);
        created.setStatus(AsyncTaskStatus.RUNNING);
        assertEquals(AsyncTaskStatus.SUBMITTED, store.load("task").getStatus());

        AsyncTask saved = store.save(created, 0);
        assertEquals(1, saved.getVersion());
        assertEquals(AsyncTaskStatus.RUNNING, store.load("task").getStatus());
        expect(AsyncTaskVersionConflictException.class, () -> store.save(created, 0));
        expect(IllegalStateException.class,
            () -> store.save(AsyncTaskTestSupport.task("missing", AsyncTaskStatus.RUNNING, 1, 2), 0));
        expect(IllegalStateException.class, () -> store.create(original));
        assertNull(store.load("missing"));
    }

    @Test
    public void shouldClaimOnlyDueQueryableTasksInOrderAndLimit() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(AsyncTaskTestSupport.task("late", AsyncTaskStatus.RUNNING, 20, 100));
        store.create(AsyncTaskTestSupport.task("early", AsyncTaskStatus.SUBMITTED, 10, 100));
        store.create(AsyncTaskTestSupport.task("future", AsyncTaskStatus.RUNNING, 60, 100));
        store.create(AsyncTaskTestSupport.task("terminal", AsyncTaskStatus.SUCCEEDED, 1, 100));

        List<AsyncTask> claimed = store.claimDueTasks("worker", 50, 100, 1);
        assertEquals(1, claimed.size());
        assertEquals("early", claimed.get(0).getId());
        assertEquals(1, claimed.get(0).getVersion());
        assertEquals(150, claimed.get(0).getLeaseUntil());

        List<AsyncTask> next = store.claimDueTasks("other", 50, 100, 10);
        assertEquals(1, next.size());
        assertEquals("late", next.get(0).getId());
    }

    @Test
    public void shouldRenewReleaseExpireAndFenceLeases() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(AsyncTaskTestSupport.task("task", AsyncTaskStatus.RUNNING, 0, 1000));
        AsyncTask first = store.claimDueTasks("a", 10, 100, 1).get(0);
        AsyncTask renewed = store.renewLease("task", "a", first.getLeaseId(), 20, 200);
        assertEquals(200, renewed.getLeaseUntil());
        assertEquals(first.getVersion(), renewed.getVersion());
        expect(IllegalStateException.class,
            () -> store.renewLease("task", "a", "stale", 20, 300));

        store.releaseLease("task", "wrong", first.getLeaseId());
        assertEquals("a", store.load("task").getLeaseOwner());
        store.releaseLease("task", "a", first.getLeaseId());
        assertNull(store.load("task").getLeaseOwner());

        AsyncTask second = store.claimDueTasks("b", 30, 100, 1).get(0);
        first.setStatus(AsyncTaskStatus.SUCCEEDED);
        expect(AsyncTaskVersionConflictException.class, () -> store.save(first, first.getVersion()));
        store.releaseLease("task", "a", first.getLeaseId());
        assertEquals("b", store.load("task").getLeaseOwner());
        assertNotEquals(first.getLeaseId(), second.getLeaseId());
    }

    @Test
    public void shouldRejectSaveAfterLeaseExpires() throws Exception {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(AsyncTaskTestSupport.task("expired", AsyncTaskStatus.RUNNING, 0, Long.MAX_VALUE));
        AsyncTask claimed = store.claimDueTasks("worker", store.currentTimeMillis(), 1, 1).get(0);
        Thread.sleep(5);
        claimed.setStatus(AsyncTaskStatus.SUCCEEDED);
        expect(IllegalStateException.class, () -> store.save(claimed, claimed.getVersion()));
    }

    @Test
    public void shouldRejectSavingWithMismatchedActiveLease() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(AsyncTaskTestSupport.task("leased", AsyncTaskStatus.RUNNING, 0, Long.MAX_VALUE));
        AsyncTask claimed = store.claimDueTasks("worker", store.currentTimeMillis(), 60_000, 1).get(0);
        AsyncTask forged = claimed.copy();
        forged.setLeaseId("wrong");
        expect(IllegalStateException.class, () -> store.save(forged, forged.getVersion()));
    }

    @Test
    public void shouldMakeCancellationMonotonicAndIgnoreTerminalTasks() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(AsyncTaskTestSupport.task("active", AsyncTaskStatus.RUNNING, 0, 100));
        store.create(AsyncTaskTestSupport.task("done", AsyncTaskStatus.SUCCEEDED, 0, 100));
        assertTrue(store.requestCancellation("active"));
        assertFalse(store.requestCancellation("active"));
        assertFalse(store.requestCancellation("done"));
        assertFalse(store.requestCancellation("missing"));

        AsyncTask active = store.load("active");
        active.setCancellationRequested(false);
        AsyncTask saved = store.save(active, active.getVersion());
        assertTrue(saved.isCancellationRequested());
    }

    @Test
    public void shouldAllowOnlyOneConcurrentClaim() throws Exception {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        store.create(AsyncTaskTestSupport.task("task", AsyncTaskStatus.RUNNING, 0, 1000));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<List<AsyncTask>> a = pool.submit(() -> { start.await(); return store.claimDueTasks("a", 10, 100, 1); });
        Future<List<AsyncTask>> b = pool.submit(() -> { start.await(); return store.claimDueTasks("b", 10, 100, 1); });
        start.countDown();
        assertEquals(1, a.get().size() + b.get().size());
        pool.shutdownNow();
    }

    @Test
    public void shouldValidateStoreArguments() {
        InMemoryAsyncTaskStore store = new InMemoryAsyncTaskStore();
        expect(IllegalArgumentException.class, () -> store.create(null));
        expect(IllegalArgumentException.class, () -> store.create(new AsyncTask()));
        expect(IllegalArgumentException.class, () -> store.claimDueTasks(null, 0, 1, 1));
        expect(IllegalArgumentException.class, () -> store.claimDueTasks("w", 0, 0, 1));
        expect(IllegalArgumentException.class, () -> store.claimDueTasks("w", 0, 1, 0));
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try { runnable.run(); fail("Expected " + type.getName()); }
        catch (Throwable error) { if (!type.isInstance(error)) throw error; }
    }
}
