package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.extractor.ingestion.*;

import java.sql.*;
import java.util.*;

/**
 * 基于 JDBC 的摄取操作状态机和冻结计划持久化实现。
 *
 * <p>操作记录与对应计划在同一行首次写入，保证恢复时不会只看到操作而缺失原始计划。
 * 阶段迁移使用数据库条件更新实现跨进程 CAS，已完成操作不会进入恢复扫描。</p>
 */
public final class JdbcGraphIngestionOperationStore extends JdbcGraphStoreSupport implements GraphIngestionOperationStore {
    JdbcGraphIngestionOperationStore(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 按 operationId 读取最新操作快照；不存在时返回 {@code null}。
     */
    @Override
    public GraphIngestionOperation get(String id) {
        if (id == null) return null;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT operation_payload FROM " + table("ingestion_operations") + " WHERE operation_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? parse(r.getBytes(1), GraphIngestionOperation.class) : null;
            }
        } catch (SQLException e) {
            throw failure("get ingestion operation", e);
        }
    }

    /**
     * JDBC 实现会持久化操作和计划，因此支持进程重启后的恢复。
     */
    @Override
    public boolean isRecoverySupported() {
        return true;
    }

    /**
     * 原子创建操作及其冻结计划。
     *
     * <p>写入前验证 operationId、Space 和 documentId 完全一致；主键冲突表示该操作已经创建，
     * 此时返回 {@code false} 且不会覆盖原计划。</p>
     */
    @Override
    public boolean createIfAbsent(GraphIngestionOperation op, GraphIngestionPlan plan) {
        if (op == null || plan == null) throw new IllegalArgumentException("operation and plan must not be null");
        if (!op.getOperationId().equals(plan.getMutation().getOperationId()) || !op.getSpace().equals(plan.getSpace()) || !op.getDocumentId().equals(plan.getDocumentId()))
            throw new IllegalArgumentException("operation and plan identity do not match");
        String sql = "INSERT INTO " + table("ingestion_operations") + " (operation_id,space_name,document_id,expected_revision,plan_fingerprint,stage,updated_at,failure_message,operation_payload,plan_payload) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, op.getOperationId());
            s.setString(2, op.getSpace());
            s.setString(3, op.getDocumentId());
            s.setLong(4, op.getExpectedRevision());
            s.setString(5, op.getPlanFingerprint());
            s.setString(6, op.getStage().name());
            s.setLong(7, op.getUpdatedAtMillis());
            s.setString(8, op.getFailureMessage());
            s.setBytes(9, bytes(op));
            s.setBytes(10, bytes(plan));
            s.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isConstraint(e)) return false;
            throw failure("create ingestion operation", e);
        }
    }

    /**
     * 在阶段和不可变身份均匹配时推进操作状态；数据库 UPDATE 的阶段条件决定最终 CAS 结果。
     */
    @Override
    public boolean compareAndSet(String id, GraphIngestionOperation.Stage expected, GraphIngestionOperation next) {
        if (expected == null || next == null) throw new IllegalArgumentException("expected and next must not be null");
        GraphIngestionOperation current = get(id);
        if (current == null || current.getStage() != expected || !sameIdentity(current, next)) return false;
        String sql = "UPDATE " + table("ingestion_operations") + " SET stage=?,updated_at=?,failure_message=?,operation_payload=? WHERE operation_id=? AND stage=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, next.getStage().name());
            s.setLong(2, next.getUpdatedAtMillis());
            s.setString(3, next.getFailureMessage());
            s.setBytes(4, bytes(next));
            s.setString(5, id);
            s.setString(6, expected.name());
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("compare and set ingestion operation", e);
        }
    }

    /**
     * 读取操作首次创建时冻结的原始摄取计划。
     */
    @Override
    public GraphIngestionPlan getPlan(String id) {
        if (id == null) return null;
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("SELECT plan_payload FROM " + table("ingestion_operations") + " WHERE operation_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? parse(r.getBytes(1), GraphIngestionPlan.class) : null;
            }
        } catch (SQLException e) {
            throw failure("get ingestion plan", e);
        }
    }

    /**
     * 按更新时间、operationId 稳定排序扫描未完成操作，并通过 JDBC 最大行数限制返回规模。
     */
    @Override
    public List<GraphIngestionOperation> listRecoverableOperations(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        List<GraphIngestionOperation> out = new ArrayList<>();
        String sql = "SELECT operation_payload FROM " + table("ingestion_operations")
            + " WHERE stage<>? ORDER BY updated_at,operation_id";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, GraphIngestionOperation.Stage.COMPLETED.name());
            s.setMaxRows(limit);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(parse(r.getBytes(1), GraphIngestionOperation.class));
            }
            return Collections.unmodifiableList(out);
        } catch (SQLException e) {
            throw failure("list recoverable ingestion operations", e);
        }
    }

    /**
     * 比较状态迁移期间禁止变化的操作身份字段。
     */
    private static boolean sameIdentity(GraphIngestionOperation a, GraphIngestionOperation b) {
        return a.getOperationId().equals(b.getOperationId()) && a.getSpace().equals(b.getSpace()) && a.getDocumentId().equals(b.getDocumentId()) && a.getExpectedRevision() == b.getExpectedRevision() && a.getPlanFingerprint().equals(b.getPlanFingerprint());
    }

    /**
     * 判断是否为主键等数据库完整性约束异常。
     */
    private static boolean isConstraint(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }
}
