package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.importing.*;

import java.sql.*;
import java.util.*;

/**
 * 基于 JDBC 的异步图导入任务快照存储。
 *
 * <p>每个 taskId 只保留最新不可变快照。保存时先尝试更新、再尝试插入；若并发插入发生唯一键
 * 冲突，则重新进入更新路径，以便多个进程安全写入同一任务。</p>
 */
public final class JdbcGraphImportTaskStore extends JdbcGraphStoreSupport implements GraphImportTaskStore {
    JdbcGraphImportTaskStore(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 保存任务的最新快照；传入 {@code null} 时与内存实现一致地忽略。
     */
    @Override
    public void save(GraphImportTask task) {
        if (task == null) return;
        String update = "UPDATE " + table("import_tasks") + " SET submitted_at=?,payload=? WHERE task_id=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(update)) {
            s.setLong(1, task.getSubmittedAtMillis());
            s.setBytes(2, bytes(task));
            s.setString(3, task.getId());
            if (s.executeUpdate() == 1) return;
        } catch (SQLException e) {
            throw failure("update import task", e);
        }
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("INSERT INTO " + table("import_tasks") + " (task_id,submitted_at,payload) VALUES (?,?,?)")) {
            s.setString(1, task.getId());
            s.setLong(2, task.getSubmittedAtMillis());
            s.setBytes(3, bytes(task));
            s.executeUpdate();
        } catch (SQLException e) {
            if (isConstraint(e)) {
                // 其他进程在 UPDATE 与 INSERT 之间创建了记录，重新执行即可进入更新分支。
                save(task);
                return;
            }
            throw failure("save import task", e);
        }
    }

    /**
     * 按任务 ID 读取最新快照；ID 为空或记录不存在时返回 {@code null}。
     */
    @Override
    public GraphImportTask get(String id) {
        if (id == null) return null;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT payload FROM " + table("import_tasks") + " WHERE task_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? parse(r.getBytes(1), GraphImportTask.class) : null;
            }
        } catch (SQLException e) {
            throw failure("get import task", e);
        }
    }

    /**
     * 按提交时间倒序、taskId 正序返回全部任务的不可变快照列表。
     */
    @Override
    public List<GraphImportTask> list() {
        List<GraphImportTask> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT payload FROM " + table("import_tasks") + " ORDER BY submitted_at DESC,task_id")) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(parse(r.getBytes(1), GraphImportTask.class));
            }
            return Collections.unmodifiableList(out);
        } catch (SQLException e) {
            throw failure("list import tasks", e);
        }
    }

    /**
     * 删除指定任务；只有实际删除记录时返回 {@code true}。
     */
    @Override
    public boolean remove(String id) {
        if (id == null) return false;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM " + table("import_tasks") + " WHERE task_id=?")) {
            s.setString(1, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("remove import task", e);
        }
    }

    /**
     * 判断插入失败是否由唯一键等完整性约束触发。
     */
    private static boolean isConstraint(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
