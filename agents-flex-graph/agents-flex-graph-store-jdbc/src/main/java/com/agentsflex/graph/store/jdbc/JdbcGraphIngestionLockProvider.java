package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionLockProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.UUID;

/**
 * 基于唯一键和未提交事务的跨进程文档排他锁。
 *
 * <p>每个活动租约持有一个 JDBC 连接。连接断开时数据库会回滚插入，因此进程崩溃不会留下
 * 永久锁记录；调用方应为锁连接预留足够的连接池容量。</p>
 *
 * <p>锁表以 {@code space + documentId} 为主键。第一个获取者在事务中插入但不提交，竞争者的
 * 同主键插入由数据库阻塞；持有者释放时删除记录并提交，等待者随后即可完成插入并成为新持有者。
 * 不同文档键互不阻塞。</p>
 */
public final class JdbcGraphIngestionLockProvider extends JdbcGraphStoreSupport
    implements GraphIngestionLockProvider {

    JdbcGraphIngestionLockProvider(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 获取文档级排他锁。
     *
     * <p>查询超时由毫秒配置向上取整为 JDBC 秒数，并限制到 int 范围。成功返回的租约必须关闭；
     * 超时或瞬时数据库错误会包装为包含文档键的运行时异常。</p>
     */
    @Override
    public Lease acquire(String space, String documentId) {
        space = requireText(space, "space");
        documentId = requireText(documentId, "documentId");
        String owner = UUID.randomUUID().toString();
        Connection connection = null;
        try {
            connection = connection();
            connection.setAutoCommit(false);
            // 此 INSERT 在租约存续期内故意不提交，同主键竞争者由数据库唯一键锁负责排队。
            String sql = "INSERT INTO " + table("ingestion_locks")
                + " (space_name,document_id,owner_id,acquired_at) VALUES (?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                long timeoutSeconds = config.getLockWaitMillis() / 1000L
                    + (config.getLockWaitMillis() % 1000L == 0L ? 0L : 1L);
                statement.setQueryTimeout((int) Math.min(Math.max(1L, timeoutSeconds), Integer.MAX_VALUE));
                statement.setString(1, space);
                statement.setString(2, documentId);
                statement.setString(3, owner);
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return new LeaseImpl(connection, space, documentId, owner);
        } catch (SQLException error) {
            rollback(connection);
            closeConnection(connection);
            if (error instanceof SQLTimeoutException || error instanceof SQLTransientException) {
                throw new IllegalStateException(
                    "Timed out waiting for graph ingestion lock: " + space + "/" + documentId, error);
            }
            throw failure("acquire graph ingestion lock", error);
        }
    }

    /**
     * 持有锁事务和连接的租约实现；close 使用同步和 closed 标记保证正常情况下幂等释放。
     */
    private final class LeaseImpl implements Lease {
        private final Connection connection;
        private final String space;
        private final String documentId;
        private final String owner;
        private boolean closed;

        private LeaseImpl(Connection connection, String space, String documentId, String owner) {
            this.connection = connection;
            this.space = space;
            this.documentId = documentId;
            this.owner = owner;
        }

        /**
         * 删除仅属于当前 owner 的锁记录并提交事务，随后无论成功与否都关闭连接。
         */
        @Override
        public synchronized void close() {
            if (closed) return;
            String sql = "DELETE FROM " + table("ingestion_locks")
                + " WHERE space_name=? AND document_id=? AND owner_id=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, space);
                statement.setString(2, documentId);
                statement.setString(3, owner);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Graph ingestion lock ownership was lost: "
                        + space + "/" + documentId);
                }
                connection.commit();
                closed = true;
            } catch (SQLException error) {
                rollback(connection);
                throw failure("release graph ingestion lock", error);
            } finally {
                closeConnection(connection);
            }
        }
    }

    /**
     * 尽力关闭锁专用连接，不覆盖获取或释放阶段的原始异常。
     */
    private static void closeConnection(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
