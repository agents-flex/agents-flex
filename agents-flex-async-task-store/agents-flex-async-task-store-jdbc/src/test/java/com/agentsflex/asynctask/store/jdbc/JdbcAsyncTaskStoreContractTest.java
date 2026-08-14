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
import com.mysql.cj.jdbc.MysqlDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
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
    private DataSource dataSource;
    private String tablePrefix;

    /**
     * 每个测试创建隔离的内存数据库，并验证 Schema 初始化可以重复执行。
     */
    @Before
    public void setUp() {
        tablePrefix = "test_async_" + UUID.randomUUID().toString().replace("-", "") + "_";
        String mysqlUrl = System.getProperty("mysql.test.url");
        if (mysqlUrl == null) {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:async_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            dataSource = h2;
        } else {
            MysqlDataSource mysql = new MysqlDataSource();
            mysql.setURL(mysqlUrl);
            mysql.setUser(System.getProperty("mysql.test.user", "root"));
            mysql.setPassword(requiredEnv("MYSQL_TEST_PASSWORD"));
            dataSource = mysql;
        }
        JdbcAsyncTaskStoreConfig config = JdbcAsyncTaskStoreConfig.builder(dataSource)
            .tablePrefix(tablePrefix).build();
        config.schema().createIfNotExists();
        config.schema().createIfNotExists();
        store = config.store();
    }

    @After
    public void tearDown() throws Exception {
        if (dataSource == null || tablePrefix == null) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + tablePrefix + "tasks");
        }
    }

    /**
     * 创建后应隔离调用方修改，CAS 保存成功递增版本，旧版本必须失败。
     */
    @Test
    public void shouldPersistDefensiveSnapshotAndEnforceCas() {
        AsyncTask source = task("task", AsyncTaskStatus.SUBMITTED, 10, 100);
        AsyncTask created = store.create(source);
        source.setStatus(AsyncTaskStatus.FAILED);
        assertEquals(AsyncTaskStatus.SUBMITTED, store.load("task").getStatus());
        created.setStatus(AsyncTaskStatus.RUNNING);
        assertEquals(1, store.save(created, 0).getVersion());
        expect(AsyncTaskVersionConflictException.class, () -> store.save(created, 0));
        assertNull(store.load("missing"));
    }

    /**
     * 提交队列先按优先级，再按计划时间领取；未来任务不得提前提交。
     */
    @Test
    public void shouldClaimDueSubmissionsByPriority() {
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

    /**
     * 即使准入策略拒绝供应商，也必须领取已取消或已过期任务，以便 Worker 写入本地终态。
     */
    @Test
    public void shouldClaimCanceledAndExpiredSubmissionsWithoutAdmission() {
        AsyncTask canceled = submission("canceled", 0, 0);
        canceled.setCancellationRequested(true);
        AsyncTask expired = submission("expired", 0, 0);
        expired.setDeadlineAt(50);
        store.create(canceled);
        store.create(expired);

        List<AsyncTask> claimed = store.claimDueSubmissions(
            "worker", 100, 1000, 10, (candidate, all, now) -> false);
        assertEquals(2, claimed.size());
    }

    /**
     * 两个线程同时争抢同一到期任务时，数据库条件更新只允许一个 Worker 成功。
     */
    @Test
    public void shouldAllowOnlyOneConcurrentClaim() throws Exception {
        store.create(task("race", AsyncTaskStatus.RUNNING, 0, 100));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<AsyncTask>> a = pool.submit(() -> {
                start.await();
                return store.claimDueTasks("a", 10, 1000, 1);
            });
            Future<List<AsyncTask>> b = pool.submit(() -> {
                start.await();
                return store.claimDueTasks("b", 10, 1000, 1);
            });
            start.countDown();
            assertEquals(1, a.get().size() + b.get().size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 提交 Worker 崩溃后结果不可判定，必须转为 SUBMIT_UNKNOWN 且不得自动重复提交。 */
    @Test
    public void shouldMarkExpiredSubmittingTaskUnknownWithoutResubmitting() {
        store.create(submission("submit-once", 1, 0));
        assertEquals(1, store.claimDueSubmissions(
            "dead-worker", 10, 20, 1, (candidate, all, now) -> true).size());

        assertTrue(store.claimDueSubmissions(
            "new-worker", 30, 20, 1, (candidate, all, now) -> true).isEmpty());
        AsyncTask recovered = store.load("submit-once");
        assertEquals(AsyncTaskStatus.SUBMIT_UNKNOWN, recovered.getStatus());
        assertNull(recovered.getLeaseOwner());
    }

    /**
     * 租约只能由 owner 和 leaseId 同时匹配的 Worker 续期或释放，取消标记保持单调且不能修改终态。
     */
    @Test
    public void shouldFenceLeaseAndKeepCancellationMonotonic() {
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
        store.create(task("submit-unknown", AsyncTaskStatus.SUBMIT_UNKNOWN, 0, 100));
        assertFalse(store.requestCancellation("submit-unknown"));
    }

    private AsyncTask submission(String id, int priority, long at) {
        AsyncTask t = task(id, AsyncTaskStatus.PENDING_SUBMIT, 0, 100);
        t.setPriority(priority);
        t.setScheduledSubmitAt(at);
        return t;
    }

    private AsyncTask task(String id, AsyncTaskStatus status, long next, long created) {
        AsyncTask t = new AsyncTask();
        t.setId(id);
        t.setHandlerKey("test");
        t.setStatus(status);
        t.setNextQueryAt(next);
        t.setCreatedAt(created);
        t.setProviderKey("provider");
        return t;
    }

    private void expect(Class<? extends Throwable> type, Runnable call) {
        try {
            call.run();
            fail("应抛出 " + type.getName());
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw error;
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
