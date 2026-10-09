package com.agentsflex.graph.store.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 创建 JDBC Graph Store 所需表和索引的轻量 Schema 初始化器。
 *
 * <p>DDL 使用 {@code CREATE TABLE IF NOT EXISTS}，索引通过元数据检测后创建，因此同一 Schema
 * 可以重复初始化。该能力主要用于测试、示例和简单部署；它不会修改已经存在的列定义，也不替代
 * 正式环境中的版本化数据库迁移。</p>
 */
public final class JdbcGraphStoreSchema extends JdbcGraphStoreSupport {
    JdbcGraphStoreSchema(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 创建尚不存在的表和索引。
     *
     * <p>该方法是 {@link #initialize()} 的语义化别名，用于与项目中其他 JDBC 模块的 Schema API
     * 保持一致。</p>
     */
    public void createIfNotExists() {
        initialize();
    }

    /**
     * 初始化全部表结构。
     *
     * <p>关系列保存查询条件、排序字段和 CAS 版本；{@code payload} 列保存完整不可变快照。
     * 表名和二进制列类型来自经过白名单校验的配置，因此可以安全拼接到 DDL。</p>
     *
     * @throws IllegalStateException 获取连接或执行 DDL 失败时抛出
     */
    public void initialize() {
        String p = config.getTablePrefix();
        String b = config.getBinaryColumnType();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            // 文档当前快照和不可变版本历史分表保存：删除当前状态不会破坏来源审计历史。
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "document_states (space_name VARCHAR(191) NOT NULL, document_id VARCHAR(191) NOT NULL, revision BIGINT NOT NULL, status VARCHAR(32) NOT NULL, operation_id VARCHAR(191), content_hash VARCHAR(191) NOT NULL, document_version VARCHAR(191), schema_version VARCHAR(191), extraction_fingerprint VARCHAR(191), source_updated_at BIGINT NOT NULL, batch_id VARCHAR(191), committed_at BIGINT NOT NULL, payload " + b + " NOT NULL, PRIMARY KEY(space_name,document_id))");
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "document_versions (space_name VARCHAR(191) NOT NULL, document_id VARCHAR(191) NOT NULL, revision BIGINT NOT NULL, operation_id VARCHAR(191), committed_at BIGINT NOT NULL, payload " + b + " NOT NULL, PRIMARY KEY(space_name,document_id,revision))");
            createIndex(c, s, p + "document_operation_idx", p + "document_versions", "space_name,document_id,operation_id");
            // 操作和冻结计划同处一行，避免恢复流程读取到不完整的两阶段写入。
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "ingestion_operations (operation_id VARCHAR(191) PRIMARY KEY, space_name VARCHAR(191) NOT NULL, document_id VARCHAR(191) NOT NULL, expected_revision BIGINT NOT NULL, plan_fingerprint VARCHAR(191) NOT NULL, stage VARCHAR(32) NOT NULL, updated_at BIGINT NOT NULL, failure_message VARCHAR(2000), operation_payload " + b + " NOT NULL, plan_payload " + b + " NOT NULL)");
            createIndex(c, s, p + "ingestion_recovery_idx", p + "ingestion_operations", "stage,updated_at,operation_id");
            // 审核查询依赖投影字段；完整计划和审核上下文仍从 payload 恢复。
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "review_tasks (task_id VARCHAR(191) PRIMARY KEY, space_name VARCHAR(191) NOT NULL, document_id VARCHAR(191) NOT NULL, status VARCHAR(32) NOT NULL, review_version BIGINT NOT NULL, operation_id VARCHAR(191), updated_at BIGINT NOT NULL, created_at BIGINT NOT NULL, reason VARCHAR(2000), actor VARCHAR(191), payload " + b + " NOT NULL)");
            createIndex(c, s, p + "review_query_idx", p + "review_tasks", "space_name,document_id,status,updated_at");
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "import_tasks (task_id VARCHAR(191) PRIMARY KEY, submitted_at BIGINT NOT NULL, payload " + b + " NOT NULL)");
            // 实体主体和名称倒排索引分离，使一个节点可拥有多个名称，同时强制名称归属唯一。
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "entities (space_name VARCHAR(191) NOT NULL, node_id VARCHAR(191) NOT NULL, entity_type VARCHAR(191) NOT NULL, canonical_name VARCHAR(1000) NOT NULL, aliases_json TEXT NOT NULL, properties_json TEXT NOT NULL, PRIMARY KEY(space_name,node_id))");
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "entity_names (space_name VARCHAR(191) NOT NULL, entity_type VARCHAR(191) NOT NULL, name_hash CHAR(64) NOT NULL, normalized_name VARCHAR(1000) NOT NULL, node_id VARCHAR(191) NOT NULL, PRIMARY KEY(space_name,entity_type,name_hash))");
            // 锁行只在持有者的未提交事务中存在；连接中断会由数据库自动回滚。
            s.execute("CREATE TABLE IF NOT EXISTS " + p + "ingestion_locks (space_name VARCHAR(191) NOT NULL, document_id VARCHAR(191) NOT NULL, owner_id VARCHAR(191) NOT NULL, acquired_at BIGINT NOT NULL, PRIMARY KEY(space_name,document_id))");
        } catch (SQLException e) {
            throw failure("initialize graph store schema", e);
        }
    }

    /**
     * 在索引不存在时创建索引，并容忍多个应用实例同时初始化时的“检查后创建”竞态。
     */
    private static void createIndex(Connection connection, Statement statement, String name,
                                    String table, String columns) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        if (hasIndex(metadata, table, name) || hasIndex(metadata, table.toUpperCase(), name)) return;
        try {
            statement.execute("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        } catch (SQLException error) {
            // 元数据检查之后，另一个初始化器可能已经创建了同名索引；复查确认后即可视为成功。
            if (!hasIndex(metadata, table, name) && !hasIndex(metadata, table.toUpperCase(), name)) throw error;
        }
    }

    /**
     * 按数据库元数据进行大小写不敏感的索引存在性检查。
     */
    private static boolean hasIndex(DatabaseMetaData metadata, String table, String name) throws SQLException {
        try (ResultSet indexes = metadata.getIndexInfo(null, null, table, false, false)) {
            while (indexes.next()) {
                String existing = indexes.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(name)) return true;
            }
        }
        return false;
    }
}
