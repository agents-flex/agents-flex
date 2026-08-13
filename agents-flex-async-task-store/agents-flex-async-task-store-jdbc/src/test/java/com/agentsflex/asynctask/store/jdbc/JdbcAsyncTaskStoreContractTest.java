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
package com.agentsflex.asynctask.store.jdbc;

import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.store.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/**
 * JDBC Store 契约测试。
 *
 * <p>测试使用 H2 但只依赖标准 JDBC 行为，重点保障完整快照恢复、乐观锁、调度顺序、
 * 延迟提交、租约 fencing 和并发领取，而不是只验证 SQL 能否执行。</p>
 */
public class JdbcAsyncTaskStoreContractTest {
    private JdbcAsyncTaskStore store;

    /** 每个测试创建隔离的内存数据库，并验证 Schema 初始化可以重复执行。 */
    @Before public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:async_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcAsyncTaskStoreConfig config = JdbcAsyncTaskStoreConfig.builder(dataSource).build();
        config.schema().createIfNotExists();
        config.schema().createIfNotExists();
        store = config.store();
    }

    /** 创建后应隔离调用方修改，CAS 保存成功递增版本，旧版本必须失败。 */
    @Test public void shouldPersistDefensiveSnapshotAndEnforceCas() {
        AsyncTask source = task("task", AsyncTaskStatus.SUBMITTED, 10, 100);
        AsyncTask created = store.create(source);
        source.setStatus(AsyncTaskStatus.FAILED);
        assertEquals(AsyncTaskStatus.SUBMITTED, store.load("task").getStatus());
        created.setStatus(AsyncTaskStatus.RUNNING);
        assertEquals(1, store.save(created, 0).getVersion());
        expect(AsyncTaskVersionConflictException.class, () -> store.save(created, 0));
        assertNull(store.load("missing"));
    }

    /** 提交队列先按优先级，再按计划时间领取；未来任务不得提前提交。 */
    @Test public void shouldClaimDueSubmissionsByPriority() {
        store.create(submission("low", 1, 10));
        store.create(submission("future", 100, 1000));
        store.create(submission("high", 9, 20));
        AsyncTaskAdmissionPolicy allow = (candidate, all, now) -> true;
        List<AsyncTask> claimed = store.claimDueSubmissions("worker", 100, 1000, 10, allow);
        assertEquals(2, claimed.size());
        assertEquals("high", claimed.get(0).getId());
        assertEquals("low", claimed.get(1).getId());
        assertEquals(AsyncTaskStatus.SUBMITTING, claimed.get(0).getStatus());
    }

    /** 两个线程同时争抢同一到期任务时，数据库条件更新只允许一个 Worker 成功。 */
    @Test public void shouldAllowOnlyOneConcurrentClaim() throws Exception {
        store.create(task("race", AsyncTaskStatus.RUNNING, 0, 100));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<AsyncTask>> a = pool.submit(() -> { start.await(); return store.claimDueTasks("a", 10, 1000, 1); });
            Future<List<AsyncTask>> b = pool.submit(() -> { start.await(); return store.claimDueTasks("b", 10, 1000, 1); });
            start.countDown();
            assertEquals(1, a.get().size() + b.get().size());
        } finally { pool.shutdownNow(); }
    }

    /** 租约只能由 owner 和 leaseId 同时匹配的 Worker 续期或释放，取消标记保持单调。 */
    @Test public void shouldFenceLeaseAndKeepCancellationMonotonic() {
        store.create(task("leased", AsyncTaskStatus.RUNNING, 0, 100));
        AsyncTask claimed = store.claimDueTasks("worker", 10, 100, 1).get(0);
        assertEquals(300, store.renewLease("leased", "worker", claimed.getLeaseId(), 20, 300).getLeaseUntil());
        expect(IllegalStateException.class, () -> store.renewLease("leased", "other", claimed.getLeaseId(), 20, 400));
        store.releaseLease("leased", "other", claimed.getLeaseId());
        assertEquals("worker", store.load("leased").getLeaseOwner());
        store.releaseLease("leased", "worker", claimed.getLeaseId());
        assertTrue(store.requestCancellation("leased"));
        assertFalse(store.requestCancellation("leased"));
        assertTrue(store.load("leased").isCancellationRequested());
        AsyncTask staleCancellation = store.load("leased");
        staleCancellation.setCancellationRequested(false);
        assertTrue(store.save(staleCancellation, staleCancellation.getVersion()).isCancellationRequested());
    }

    private AsyncTask submission(String id, int priority, long at) { AsyncTask t=task(id,AsyncTaskStatus.PENDING_SUBMIT,0,100);t.setPriority(priority);t.setScheduledSubmitAt(at);return t; }
    private AsyncTask task(String id, AsyncTaskStatus status, long next, long created) { AsyncTask t=new AsyncTask();t.setId(id);t.setHandlerKey("test");t.setStatus(status);t.setNextQueryAt(next);t.setCreatedAt(created);t.setProviderKey("provider");return t; }
    private void expect(Class<? extends Throwable> type, Runnable call) { try { call.run(); fail("应抛出 " + type.getName()); } catch (Throwable error) { if (!type.isInstance(error)) throw error; } }
}
