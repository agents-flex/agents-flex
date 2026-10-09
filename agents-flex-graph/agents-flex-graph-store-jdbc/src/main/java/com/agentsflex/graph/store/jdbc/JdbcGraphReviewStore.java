package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.extractor.review.*;

import java.sql.*;
import java.util.*;

/**
 * 基于 JDBC 的人工审核任务存储。
 *
 * <p>任务状态、Space、文档和更新时间投影为关系型字段以支持组合过滤，其余审核计划和上下文保存在
 * payload 中。更新操作以 reviewVersion 为条件实现跨进程乐观锁。</p>
 */
public final class JdbcGraphReviewStore extends JdbcGraphStoreSupport implements GraphReviewStore {
    JdbcGraphReviewStore(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 创建新的审核任务；taskId 已存在时抛出明确的重复任务异常。
     */
    @Override
    public GraphReviewTask create(GraphReviewTask task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        String sql = "INSERT INTO " + table("review_tasks") + " (task_id,space_name,document_id,status,review_version,operation_id,updated_at,created_at,reason,actor,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, task);
            s.executeUpdate();
            return task;
        } catch (SQLException e) {
            if (isConstraint(e)) throw new IllegalStateException("review task already exists: " + task.getTaskId(), e);
            throw failure("create review task", e);
        }
    }

    /**
     * 按 taskId 读取完整审核任务快照；不存在时返回 {@code null}。
     */
    @Override
    public GraphReviewTask findTask(String id) {
        if (id == null) return null;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT payload FROM " + table("review_tasks") + " WHERE task_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? parse(r.getBytes(1), GraphReviewTask.class) : null;
            }
        } catch (SQLException e) {
            throw failure("find review task", e);
        }
    }

    /**
     * 按 Space、文档和状态组合过滤，并按更新时间倒序、taskId 正序稳定分页。
     *
     * <p>为兼容不同 JDBC 方言，不在 SQL 中拼接 LIMIT/OFFSET，而是使用 setMaxRows 限制数据库
     * 返回上界并在结果集中跳过 offset。加法使用 long，避免极端分页参数发生整数溢出。</p>
     */
    @Override
    public List<GraphReviewTask> findTasks(GraphReviewTaskQuery q) {
        if (q == null) throw new IllegalArgumentException("query must not be null");
        StringBuilder sql = new StringBuilder("SELECT payload FROM " + table("review_tasks") + " WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!q.getSpace().isEmpty()) {
            sql.append(" AND space_name=?");
            args.add(q.getSpace());
        }
        if (!q.getDocumentId().isEmpty()) {
            sql.append(" AND document_id=?");
            args.add(q.getDocumentId());
        }
        sql.append(" AND status IN (");
        int i = 0;
        for (GraphReviewTaskStatus status : q.getStatuses()) {
            if (i++ > 0) sql.append(',');
            sql.append('?');
            args.add(status.name());
        }
        // 不同数据库的 LIMIT/OFFSET 语法不一致，因此用 JDBC 行数上限配合 Java 跳过 offset。
        sql.append(") ORDER BY updated_at DESC,task_id");
        List<GraphReviewTask> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql.toString())) {
            for (i = 0; i < args.size(); i++) s.setObject(i + 1, args.get(i));
            long requestedRows = (long) q.getOffset() + q.getLimit();
            s.setMaxRows((int) Math.min(requestedRows, Integer.MAX_VALUE));
            try (ResultSet r = s.executeQuery()) {
                int skipped = 0;
                while (r.next() && out.size() < q.getLimit()) {
                    if (skipped++ < q.getOffset()) continue;
                    out.add(parse(r.getBytes(1), GraphReviewTask.class));
                }
            }
            return Collections.unmodifiableList(out);
        } catch (SQLException e) {
            throw failure("find review tasks", e);
        }
    }

    /**
     * 以 expected reviewVersion 为条件原子更新任务；新版本必须严格增加一。
     *
     * @throws IllegalStateException 任务不存在或版本已经被其他写入者推进时抛出
     */
    @Override
    public GraphReviewTask update(GraphReviewTask task, long expected) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        if (task.getReviewVersion() != expected + 1L)
            throw new IllegalArgumentException("task reviewVersion must increase by one");
        String sql = "UPDATE " + table("review_tasks") + " SET space_name=?,document_id=?,status=?,review_version=?,operation_id=?,updated_at=?,reason=?,actor=?,payload=? WHERE task_id=? AND review_version=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, task.getPlan().getSpace());
            s.setString(2, task.getPlan().getDocumentId());
            s.setString(3, task.getStatus().name());
            s.setLong(4, task.getReviewVersion());
            s.setString(5, task.getOperationId());
            s.setLong(6, task.getUpdatedAtMillis());
            s.setString(7, task.getReason());
            s.setString(8, task.getActor());
            s.setBytes(9, bytes(task));
            s.setString(10, task.getTaskId());
            s.setLong(11, expected);
            if (s.executeUpdate() != 1) {
                if (findTask(task.getTaskId()) == null)
                    throw new IllegalStateException("review task was not found: " + task.getTaskId());
                throw new IllegalStateException("review task version conflict: " + task.getTaskId());
            }
            return task;
        } catch (SQLException e) {
            throw failure("update review task", e);
        }
    }

    /**
     * 绑定审核任务首次插入所需的查询投影列和完整 payload。
     */
    private void bind(PreparedStatement s, GraphReviewTask t) throws SQLException {
        s.setString(1, t.getTaskId());
        s.setString(2, t.getPlan().getSpace());
        s.setString(3, t.getPlan().getDocumentId());
        s.setString(4, t.getStatus().name());
        s.setLong(5, t.getReviewVersion());
        s.setString(6, t.getOperationId());
        s.setLong(7, t.getUpdatedAtMillis());
        s.setLong(8, t.getCreatedAtMillis());
        s.setString(9, t.getReason());
        s.setString(10, t.getActor());
        s.setBytes(11, bytes(t));
    }

    /**
     * 判断是否为唯一键等数据库完整性约束异常。
     */
    private static boolean isConstraint(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
