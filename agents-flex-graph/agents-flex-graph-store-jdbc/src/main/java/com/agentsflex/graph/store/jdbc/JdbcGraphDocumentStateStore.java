package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentState;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentStateStore;
import com.agentsflex.graph.extractor.ingestion.GraphFactSource;

import java.sql.*;
import java.util.*;

/**
 * 基于 JDBC 的文档当前状态、版本历史和事实来源查询实现。
 *
 * <p>当前状态表以 {@code space + documentId} 为主键，通过 revision 条件更新实现跨进程 CAS；
 * 历史表以 revision 追加保存不可变快照。查询字段单独投影到列，完整状态保存在 payload 中。</p>
 */
public final class JdbcGraphDocumentStateStore extends JdbcGraphStoreSupport implements GraphDocumentStateStore {
    JdbcGraphDocumentStateStore(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 查询指定 Space 中某个文档的最新状态；不存在时返回 {@code null}。
     */
    @Override
    public GraphDocumentState findCurrent(String space, String documentId) {
        space = requireText(space, "space");
        documentId = requireText(documentId, "documentId");
        String sql = "SELECT payload FROM " + table("document_states") + " WHERE space_name=? AND document_id=?";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, space);
            s.setString(2, documentId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? parse(r.getBytes(1), GraphDocumentState.class) : null;
            }
        } catch (SQLException e) {
            throw failure("find current document state", e);
        }
    }

    /**
     * 按 documentId 稳定排序列出指定 Space 的全部当前状态，返回不可变列表。
     */
    @Override
    public List<GraphDocumentState> list(String space) {
        space = requireText(space, "space");
        List<GraphDocumentState> out = new ArrayList<>();
        String sql = "SELECT payload FROM " + table("document_states") + " WHERE space_name=? ORDER BY document_id";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, space);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(parse(r.getBytes(1), GraphDocumentState.class));
            }
            return Collections.unmodifiableList(out);
        } catch (SQLException e) {
            throw failure("list document states", e);
        }
    }

    /**
     * 按操作 ID 查找对应状态，优先检查当前快照，再回查按 revision 降序排列的历史快照。
     */
    @Override
    public GraphDocumentState findByOperationId(String space, String documentId, String operationId) {
        space = requireText(space, "space");
        documentId = requireText(documentId, "documentId");
        if (operationId == null || operationId.trim().isEmpty()) return null;
        GraphDocumentState current = findCurrent(space, documentId);
        if (current != null && operationId.equals(current.getOperationId())) return current;
        String sql = "SELECT payload FROM " + table("document_versions") + " WHERE space_name=? AND document_id=? AND operation_id=? ORDER BY revision DESC";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, space);
            s.setString(2, documentId);
            s.setString(3, operationId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    GraphDocumentState value = parse(r.getBytes(1), GraphDocumentState.class);
                    if (operationId.equals(value.getOperationId())) return value;
                }
            }
            return null;
        } catch (SQLException e) {
            throw failure("find document state by operation", e);
        }
    }

    /**
     * 原子推进当前文档状态。
     *
     * <p>新状态 revision 必须等于 {@code expected + 1}。首次写入使用主键唯一约束竞争，后续写入
     * 在 UPDATE 条件中携带 expected revision，因此多个进程同时提交时最多一个成功。</p>
     */
    @Override
    public boolean compareAndSet(String space, String documentId, long expected, GraphDocumentState state) {
        space = requireText(space, "space");
        documentId = requireText(documentId, "documentId");
        if (state == null) throw new IllegalArgumentException("newState must not be null");
        if (!space.equals(state.getSpace()) || !documentId.equals(state.getDocumentId()))
            throw new IllegalArgumentException("newState identity does not match the storage key");
        if (expected < 0L || state.getRevision() != expected + 1L) return false;
        Connection c = null;
        try {
            c = connection();
            c.setAutoCommit(false);
            int n;
            if (expected == 0) {
                // 首次状态没有旧行可更新，依靠复合主键让并发创建者只产生一个赢家。
                try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table("document_states") + " (space_name,document_id,revision,status,operation_id,content_hash,document_version,schema_version,extraction_fingerprint,source_updated_at,batch_id,committed_at,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    bind(s, state);
                    n = s.executeUpdate();
                }
            } else {
                // 后续状态把旧 revision 放进 WHERE，更新行数即为数据库级 CAS 结果。
                try (PreparedStatement s = c.prepareStatement("UPDATE " + table("document_states") + " SET revision=?,status=?,operation_id=?,content_hash=?,document_version=?,schema_version=?,extraction_fingerprint=?,source_updated_at=?,batch_id=?,committed_at=?,payload=? WHERE space_name=? AND document_id=? AND revision=?")) {
                    bindUpdate(s, state, space, documentId, expected);
                    n = s.executeUpdate();
                }
            }
            if (n == 1) c.commit();
            else c.rollback();
            return n == 1;
        } catch (SQLException e) {
            rollback(c);
            if (expected == 0 && isConstraint(e)) {
                return false;
            }
            throw failure("compare and set document state", e);
        } finally {
            close(c);
        }
    }

    /**
     * 仅当当前 revision 等于 expected 时删除当前状态，历史版本不受影响。
     */
    @Override
    public boolean remove(String space, String documentId, long expected) {
        space = requireText(space, "space");
        documentId = requireText(documentId, "documentId");
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM " + table("document_states") + " WHERE space_name=? AND document_id=? AND revision=?")) {
            s.setString(1, space);
            s.setString(2, documentId);
            s.setLong(3, expected);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("remove document state", e);
        }
    }

    /**
     * 幂等追加一条历史版本。同一文档同一 revision 已存在时保持原记录不变。
     */
    @Override
    public void recordVersion(GraphDocumentState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("INSERT INTO " + table("document_versions") + " (space_name,document_id,revision,operation_id,committed_at,payload) VALUES (?,?,?,?,?,?)")) {
            s.setString(1, state.getSpace());
            s.setString(2, state.getDocumentId());
            s.setLong(3, state.getRevision());
            s.setString(4, state.getOperationId());
            s.setLong(5, state.getCommittedAtMillis());
            s.setBytes(6, bytes(state));
            s.executeUpdate();
        } catch (SQLException e) {
            if (!isConstraint(e)) throw failure("record document state version", e);
        }
    }

    /**
     * 按 revision 升序返回指定文档的全部历史版本。
     */
    @Override
    public List<GraphDocumentState> listVersions(String space, String documentId) {
        space = requireText(space, "space");
        documentId = requireText(documentId, "documentId");
        List<GraphDocumentState> out = new ArrayList<>();
        String sql = "SELECT payload FROM " + table("document_versions") + " WHERE space_name=? AND document_id=? ORDER BY revision";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, space);
            s.setString(2, documentId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(parse(r.getBytes(1), GraphDocumentState.class));
            }
            return Collections.unmodifiableList(out);
        } catch (SQLException e) {
            throw failure("list document state versions", e);
        }
    }

    /**
     * 判断某条边是否仍被同一 Space 中除指定文档外的其他活动文档引用。
     */
    @Override
    public boolean isReferencedByOtherDocument(String space, String excluded, GraphEdgeKey edge) {
        for (GraphDocumentState s : list(space))
            if (s.getStatus() == GraphDocumentState.Status.ACTIVE && !s.getDocumentId().equals(excluded) && s.getEdgeKeys().contains(edge))
                return true;
        return false;
    }

    /**
     * 汇总同一 Space 当前活动文档中与指定边关联的事实来源。
     */
    @Override
    public List<GraphFactSource> findCurrentFactSources(String space, GraphEdgeKey edge) {
        List<GraphFactSource> out = new ArrayList<>();
        for (GraphDocumentState s : list(space))
            if (s.getStatus() == GraphDocumentState.Status.ACTIVE)
                for (GraphFactSource f : s.getFactSources()) if (f.getEdgeKey().equals(edge)) out.add(f);
        return Collections.unmodifiableList(out);
    }

    /**
     * 从不可变历史表查询指定边的全部事实来源，当前状态即使已删除也不会丢失审计记录。
     */
    @Override
    public List<GraphFactSource> findFactSourceHistory(String space, GraphEdgeKey edge) {
        space = requireText(space, "space");
        if (edge == null) throw new IllegalArgumentException("edgeKey must not be null");
        List<GraphFactSource> out = new ArrayList<>();
        String sql = "SELECT payload FROM " + table("document_versions") + " WHERE space_name=? ORDER BY document_id,revision";
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, space);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    GraphDocumentState version = parse(r.getBytes(1), GraphDocumentState.class);
                    for (GraphFactSource source : version.getFactSources())
                        if (source.getEdgeKey().equals(edge)) out.add(source);
                }
            }
            return Collections.unmodifiableList(out);
        } catch (SQLException e) {
            throw failure("find fact source history", e);
        }
    }

    /**
     * 绑定首次插入当前状态所需的投影列和完整 payload。
     */
    private void bind(PreparedStatement s, GraphDocumentState v) throws SQLException {
        s.setString(1, v.getSpace());
        s.setString(2, v.getDocumentId());
        s.setLong(3, v.getRevision());
        s.setString(4, v.getStatus().name());
        s.setString(5, v.getOperationId());
        s.setString(6, v.getContentHash());
        s.setString(7, v.getDocumentVersion());
        s.setString(8, v.getSchemaVersion());
        s.setString(9, v.getExtractionFingerprint());
        s.setLong(10, v.getSourceUpdatedAtMillis());
        s.setString(11, v.getBatchId());
        s.setLong(12, v.getCommittedAtMillis());
        s.setBytes(13, bytes(v));
    }

    /**
     * 绑定更新列以及作为 CAS 条件的文档键和 expected revision。
     */
    private void bindUpdate(PreparedStatement s, GraphDocumentState v, String space, String doc, long expected) throws SQLException {
        s.setLong(1, v.getRevision());
        s.setString(2, v.getStatus().name());
        s.setString(3, v.getOperationId());
        s.setString(4, v.getContentHash());
        s.setString(5, v.getDocumentVersion());
        s.setString(6, v.getSchemaVersion());
        s.setString(7, v.getExtractionFingerprint());
        s.setLong(8, v.getSourceUpdatedAtMillis());
        s.setString(9, v.getBatchId());
        s.setLong(10, v.getCommittedAtMillis());
        s.setBytes(11, bytes(v));
        s.setString(12, space);
        s.setString(13, doc);
        s.setLong(14, expected);
    }

    /**
     * SQLState 23 表示完整性约束冲突，用于识别并发首次写入或重复历史版本。
     */
    private static boolean isConstraint(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    /**
     * 尽力关闭手工管理的事务连接，不覆盖之前产生的异常。
     */
    private static void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
