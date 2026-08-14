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

import java.sql.*;
import java.util.*;

/**
 * 基于 JDBC 的异步任务持久化实现。
 *
 * <p>保存使用 version 条件更新实现 CAS；领取先按调度顺序读取候选，再使用 version 与租约条件更新，
 * 因而多个 Worker 即使同时看到同一任务，也只有一个能获得带唯一 leaseId 的执行权。</p>
 *
 * <p>AdmissionPolicy 是 Java 扩展点，无法通用地下推成 SQL。本实现会在候选快照上调用它，并用数据库
 * 条件更新防止同一任务重复领取；需要跨 JVM 严格共享 QPS/配额时，应传入基于共享限流系统的策略。</p>
 */
public final class JdbcAsyncTaskStore implements AsyncTaskStore {
    private final JdbcAsyncTaskStoreConfig config;
    private final String table;

    JdbcAsyncTaskStore(JdbcAsyncTaskStoreConfig config) {
        this.config = config;
        this.table = config.getTablePrefix() + "tasks";
    }

    @Override
    public long currentTimeMillis() {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT CURRENT_TIMESTAMP"); ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new IllegalStateException("Database did not return current time");
            return r.getTimestamp(1).getTime();
        } catch (SQLException e) {
            throw failure("read database time", e);
        }
    }

    @Override
    public AsyncTask create(AsyncTask task) {
        requireTask(task);
        AsyncTask stored = task.copy();
        stored.setVersion(0);
        // payload 保存完整对象，其余列是调度投影，数据库无需解析二进制正文即可筛选候选。
        String sql = "INSERT INTO " + table + " (task_id,version,status,priority,scheduled_submit_at,next_query_at,created_at,lease_owner,lease_id,lease_until,cancellation_requested,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, stored);
            s.executeUpdate();
            return stored.copy();
        } catch (SQLException e) {
            if (isConstraint(e)) throw new IllegalStateException("Async task already exists: " + task.getId(), e);
            throw failure("create async task", e);
        }
    }

    @Override
    public AsyncTask load(String taskId) {
        if (taskId == null) return null;
        try (Connection c = connection()) {
            return load(c, taskId);
        } catch (SQLException e) {
            throw failure("load async task " + taskId, e);
        }
    }

    @Override
    public AsyncTask save(AsyncTask task, long expectedVersion) {
        requireTask(task);
        AsyncTask current = load(task.getId());
        if (current == null) throw new IllegalStateException("Async task does not exist: " + task.getId());
        long now = currentTimeMillis();
        if (current.getLeaseId() != null && current.getLeaseUntil() <= now)
            throw new IllegalStateException("Async task lease has expired: " + task.getId());
        if (current.getLeaseId() != null && (!eq(current.getLeaseId(), task.getLeaseId()) || !eq(current.getLeaseOwner(), task.getLeaseOwner())))
            throw new IllegalStateException("Async task lease does not match: " + task.getId());
        AsyncTask stored = task.copy();
        // 对象层先合并取消标记，SQL 中再以 CASE 保护读取后发生的并发取消窗口。
        stored.setCancellationRequested(current.isCancellationRequested() || task.isCancellationRequested());
        stored.setVersion(expectedVersion + 1);
        String sql = "UPDATE " + table + " SET version=?,status=?,priority=?,scheduled_submit_at=?,next_query_at=?,created_at=?,lease_owner=?,lease_id=?,lease_until=?,"
            + "cancellation_requested=CASE WHEN cancellation_requested=TRUE THEN TRUE ELSE ? END,payload=? WHERE task_id=? AND version=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            bindUpdate(s, stored);
            s.setString(12, task.getId());
            s.setLong(13, expectedVersion);
            if (s.executeUpdate() != 1) throw conflict(task.getId(), expectedVersion);
            return load(task.getId());
        } catch (SQLException e) {
            throw failure("save async task", e);
        }
    }

    @Override
    public List<AsyncTask> claimDueSubmissions(String workerId, long now, long leaseMillis, int limit, AsyncTaskAdmissionPolicy policy) {
        validateClaim(workerId, leaseMillis, limit);
        if (policy == null) throw new IllegalArgumentException("admissionPolicy is required");
        markExpiredSubmissionsUnknown(now, limit * 8);
        List<AsyncTask> candidates = select("status IN (?) AND scheduled_submit_at<=? AND (lease_owner IS NULL OR lease_until<=?) ORDER BY priority DESC,scheduled_submit_at,created_at",
            new String[]{AsyncTaskStatus.PENDING_SUBMIT.name()}, now, limit * 8);
        // Java Policy 无法通用下推为 SQL；策略判断后仍由 version 条件更新决定最终领取者。
        List<AsyncTask> snapshot = all();
        List<AsyncTask> result = new ArrayList<>();
        for (AsyncTask task : candidates) {
            if (result.size() >= limit) break;
            // 取消和截止时间优先于准入，确保被暂停或限额阻塞的任务仍能由 Worker 收敛到本地终态。
            if (requiresTerminalTransition(task, now) || policy.tryAcquire(task.copy(), snapshot, now)) {
                AsyncTask claimed = claim(task, workerId, now, leaseMillis, true);
                if (claimed != null) result.add(claimed);
            }
        }
        return result;
    }

    private void markExpiredSubmissionsUnknown(long now, int max) {
        String query = "SELECT payload FROM " + table
            + " WHERE status=? AND lease_until<=? ORDER BY lease_until";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(query)) {
            s.setString(1, AsyncTaskStatus.SUBMITTING.name());
            s.setLong(2, now);
            s.setMaxRows(max);
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) markSubmitUnknown(deserialize(rows.getBytes(1)), now);
            }
        } catch (SQLException e) {
            throw failure("recover expired async task submissions", e);
        }
    }

    private void markSubmitUnknown(AsyncTask task, long now) throws SQLException {
        AsyncTask unknown = task.copy();
        unknown.setVersion(task.getVersion() + 1);
        unknown.setStatus(AsyncTaskStatus.SUBMIT_UNKNOWN);
        unknown.setLeaseOwner(null);
        unknown.setLeaseId(null);
        unknown.setLeaseUntil(0);
        unknown.setUpdatedAt(now);
        unknown.setErrorMessage("Submission worker lease expired before the result was persisted");
        String update = "UPDATE " + table + " SET version=?,status=?,lease_owner=NULL,lease_id=NULL,lease_until=0,payload=? "
            + "WHERE task_id=? AND version=? AND status=? AND lease_until<=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(update)) {
            s.setLong(1, unknown.getVersion());
            s.setString(2, unknown.getStatus().name());
            s.setBytes(3, serialize(unknown));
            s.setString(4, unknown.getId());
            s.setLong(5, task.getVersion());
            s.setString(6, AsyncTaskStatus.SUBMITTING.name());
            s.setLong(7, now);
            s.executeUpdate();
        }
    }

    @Override
    public List<AsyncTask> claimDueTasks(String workerId, long now, long leaseMillis, int limit) {
        validateClaim(workerId, leaseMillis, limit);
        List<AsyncTask> candidates = select("status IN (?,?) AND next_query_at<=? AND (lease_owner IS NULL OR lease_until<=?) ORDER BY next_query_at",
            new String[]{AsyncTaskStatus.SUBMITTED.name(), AsyncTaskStatus.RUNNING.name()}, now, limit * 4);
        List<AsyncTask> result = new ArrayList<>();
        for (AsyncTask task : candidates) {
            if (result.size() >= limit) break;
            AsyncTask claimed = claim(task, workerId, now, leaseMillis, false);
            if (claimed != null) result.add(claimed);
        }
        return result;
    }

    @Override
    public AsyncTask renewLease(String taskId, String workerId, String leaseId, long now, long leaseUntil) {
        if (leaseUntil <= now) throw new IllegalArgumentException("leaseUntil must be after now");
        String sql = "UPDATE " + table + " SET lease_until=? WHERE task_id=? AND lease_owner=? AND lease_id=? AND lease_until>?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, leaseUntil);
            s.setString(2, taskId);
            s.setString(3, workerId);
            s.setString(4, leaseId);
            s.setLong(5, now);
            if (s.executeUpdate() != 1)
                throw new IllegalStateException("Async task lease is not owned by worker: " + workerId);
            AsyncTask value = load(taskId);
            value.setLeaseUntil(leaseUntil);
            return value;
        } catch (SQLException e) {
            throw failure("renew async task lease", e);
        }
    }

    @Override
    public void releaseLease(String taskId, String workerId, String leaseId) {
        String sql = "UPDATE " + table + " SET lease_owner=NULL,lease_id=NULL,lease_until=0 WHERE task_id=? AND lease_owner=? AND lease_id=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, taskId);
            s.setString(2, workerId);
            s.setString(3, leaseId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw failure("release async task lease", e);
        }
    }

    @Override
    public boolean requestCancellation(String taskId) {
        String sql = "UPDATE " + table + " SET cancellation_requested=TRUE,version=version+1 WHERE task_id=? AND cancellation_requested=FALSE AND status NOT IN (?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, taskId);
            s.setString(2, AsyncTaskStatus.SUCCEEDED.name());
            s.setString(3, AsyncTaskStatus.FAILED.name());
            s.setString(4, AsyncTaskStatus.CANCELED.name());
            s.setString(5, AsyncTaskStatus.TRACKING_TIMED_OUT.name());
            s.setString(6, AsyncTaskStatus.SUBMIT_UNKNOWN.name());
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("request async task cancellation", e);
        }
    }

    private AsyncTask claim(AsyncTask task, String worker, long now, long leaseMillis, boolean submitting) {
        AsyncTask claimed = task.copy();
        claimed.setLeaseOwner(worker);
        claimed.setLeaseId(UUID.randomUUID().toString());
        claimed.setLeaseUntil(now + leaseMillis);
        claimed.setVersion(task.getVersion() + 1);
        if (submitting) claimed.setStatus(AsyncTaskStatus.SUBMITTING);
        // SELECT 与 UPDATE 之间可能被其他 Worker 抢占，version 与 lease 条件把领取收敛为一个。
        String sql = "UPDATE " + table + " SET version=?,status=?,lease_owner=?,lease_id=?,lease_until=?,payload=? WHERE task_id=? AND version=? AND (lease_owner IS NULL OR lease_until<=?)";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, claimed.getVersion());
            s.setString(2, claimed.getStatus().name());
            s.setString(3, worker);
            s.setString(4, claimed.getLeaseId());
            s.setLong(5, claimed.getLeaseUntil());
            s.setBytes(6, serialize(claimed));
            s.setString(7, claimed.getId());
            s.setLong(8, task.getVersion());
            s.setLong(9, now);
            return s.executeUpdate() == 1 ? claimed : null;
        } catch (SQLException e) {
            throw failure("claim async task", e);
        }
    }

    private List<AsyncTask> select(String where, String[] statuses, long now, int max) {
        List<AsyncTask> values = new ArrayList<>();
        String sql = "SELECT payload FROM " + table + " WHERE " + where;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            for (String status : statuses) s.setString(i++, status);
            s.setLong(i++, now);
            s.setLong(i, now);
            s.setMaxRows(max);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) values.add(deserialize(r.getBytes(1)));
            }
            return values;
        } catch (SQLException e) {
            throw failure("select due async tasks", e);
        }
    }

    private List<AsyncTask> all() {
        List<AsyncTask> values = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT payload FROM " + table); ResultSet r = s.executeQuery()) {
            while (r.next()) values.add(deserialize(r.getBytes(1)));
            return values;
        } catch (SQLException e) {
            throw failure("load async task snapshot", e);
        }
    }

    private boolean requiresTerminalTransition(AsyncTask task, long now) {
        return task.isCancellationRequested() || (task.getDeadlineAt() > 0 && now >= task.getDeadlineAt());
    }

    private AsyncTask load(Connection c, String id) throws SQLException {
        // 取消和租约可由轻量 SQL 单独更新，因此以投影列覆盖 payload 中可能滞后的对应字段。
        try (PreparedStatement s = c.prepareStatement("SELECT payload,cancellation_requested,version,lease_owner,lease_id,lease_until FROM " + table + " WHERE task_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                AsyncTask t = deserialize(r.getBytes(1));
                t.setCancellationRequested(r.getBoolean(2));
                t.setVersion(r.getLong(3));
                t.setLeaseOwner(r.getString(4));
                t.setLeaseId(r.getString(5));
                t.setLeaseUntil(r.getLong(6));
                return t;
            }
        }
    }

    private void bind(PreparedStatement s, AsyncTask t) throws SQLException {
        s.setString(1, t.getId());
        s.setLong(2, t.getVersion());
        s.setString(3, t.getStatus().name());
        s.setInt(4, t.getPriority());
        s.setLong(5, t.getScheduledSubmitAt());
        s.setLong(6, t.getNextQueryAt());
        s.setLong(7, t.getCreatedAt());
        s.setString(8, t.getLeaseOwner());
        s.setString(9, t.getLeaseId());
        s.setLong(10, t.getLeaseUntil());
        s.setBoolean(11, t.isCancellationRequested());
        s.setBytes(12, serialize(t));
    }

    private void bindUpdate(PreparedStatement s, AsyncTask t) throws SQLException {
        s.setLong(1, t.getVersion());
        s.setString(2, t.getStatus().name());
        s.setInt(3, t.getPriority());
        s.setLong(4, t.getScheduledSubmitAt());
        s.setLong(5, t.getNextQueryAt());
        s.setLong(6, t.getCreatedAt());
        s.setString(7, t.getLeaseOwner());
        s.setString(8, t.getLeaseId());
        s.setLong(9, t.getLeaseUntil());
        s.setBoolean(10, t.isCancellationRequested());
        s.setBytes(11, serialize(t));
    }

    private byte[] serialize(AsyncTask t) {
        return config.serializer().serialize(t);
    }

    private AsyncTask deserialize(byte[] b) {
        return config.serializer().deserialize(b, AsyncTask.class);
    }

    private Connection connection() throws SQLException {
        return config.getDataSource().getConnection();
    }

    private RuntimeException failure(String op, SQLException e) {
        return new IllegalStateException("Failed to " + op, e);
    }

    private AsyncTaskVersionConflictException conflict(String id, long expected) {
        AsyncTask actual = load(id);
        return new AsyncTaskVersionConflictException(id, expected, actual == null ? -1 : actual.getVersion());
    }

    private boolean isConstraint(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }

    private void requireTask(AsyncTask t) {
        if (t == null || t.getId() == null || t.getStatus() == null)
            throw new IllegalArgumentException("task, task.id and task.status are required");
    }

    private void validateClaim(String w, long lease, int limit) {
        if (w == null || lease <= 0 || limit <= 0) throw new IllegalArgumentException("Invalid claim arguments");
    }

    private boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
