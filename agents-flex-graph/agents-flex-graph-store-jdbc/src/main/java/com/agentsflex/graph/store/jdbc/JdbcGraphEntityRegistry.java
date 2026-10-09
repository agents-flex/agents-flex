package com.agentsflex.graph.store.jdbc;

import com.alibaba.fastjson2.JSON;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.registry.GraphEntityRegistry;
import com.agentsflex.graph.extractor.registry.GraphRegisteredEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于 JDBC 的持久化实体注册表。
 *
 * <p>实体主体以 {@code space + nodeId} 为主键；规范名称和别名经过 NFKC、空白折叠及小写转换后，
 * 以 {@code space + type + SHA-256(name)} 建立唯一索引。固定长度摘要规避 MySQL utf8mb4 长文本
 * 索引限制，同时保留 normalizedName 用于查询时防御摘要碰撞。</p>
 *
 * <p>同一节点的重复保存会合并别名和属性。现有行通过 {@code SELECT ... FOR UPDATE} 串行化更新；
 * 首次并发插入发生唯一键竞争时，事务会回滚、重新读取并合并，避免丢失另一写入者的别名。</p>
 */
public final class JdbcGraphEntityRegistry extends JdbcGraphStoreSupport implements GraphEntityRegistry {
    JdbcGraphEntityRegistry(JdbcGraphStoreConfig config) {
        super(config);
    }

    /**
     * 按 Space、实体类型和候选名称查找已注册实体。
     *
     * <p>输入名称和类型均按与写入相同的规则规范化；多个名称命中同一 nodeId 时只返回一次，
     * 返回顺序跟随候选名称首次命中的顺序。</p>
     */
    @Override
    public List<GraphRegisteredEntity> findMatches(String space, String type, Collection<String> names) {
        space = requireText(space, "space");
        type = requireText(type, "type");
        if (names == null || names.isEmpty()) return Collections.emptyList();
        List<GraphRegisteredEntity> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String lookup = "SELECT node_id FROM " + table("entity_names")
            + " WHERE space_name=? AND entity_type=? AND name_hash=? AND normalized_name=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(lookup)) {
            for (String name : names) {
                if (name == null || name.trim().isEmpty()) continue;
                String normalized = normalize(name);
                statement.setString(1, space);
                statement.setString(2, normalize(type));
                statement.setString(3, sha256(normalized));
                statement.setString(4, normalized);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) ids.add(rows.getString(1));
                }
            }
            for (String id : ids) {
                GraphRegisteredEntity entity = load(connection, space, id, false);
                if (entity != null) result.add(entity);
            }
            return Collections.unmodifiableList(result);
        } catch (SQLException error) {
            throw failure("find registered entities", error);
        }
    }

    /**
     * 在单个事务中批量合并并保存实体。
     *
     * <p>先复制输入集合，保证完整性约束重试期间处理的是稳定快照。首次插入冲突最多重试三次；
     * 真正的名称归属冲突会在重试读取到竞争者后转化为 {@link GraphExtractionException}。</p>
     */
    @Override
    public void saveAll(String space, Collection<GraphRegisteredEntity> values) {
        space = requireText(space, "space");
        if (values == null || values.isEmpty()) return;
        List<GraphRegisteredEntity> snapshot = new ArrayList<>(values);
        for (GraphRegisteredEntity value : snapshot) {
            if (value == null) throw new IllegalArgumentException("entities must not contain null elements");
        }
        SQLException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                saveAllOnce(space, snapshot);
                return;
            } catch (SQLException error) {
                lastFailure = error;
                // 并发首次插入可能撞唯一键；新事务会重新读取赢家并合并，而不是覆盖其数据。
                if (!isConstraint(error)) break;
            }
        }
        throw failure("save registered entities", lastFailure);
    }

    /**
     * 执行一次完整的校验、合并、索引重建和事务提交。
     */
    private void saveAllOnce(String space, Collection<GraphRegisteredEntity> values) throws SQLException {
        Connection connection = null;
        try {
            connection = connection();
            connection.setAutoCommit(false);
            Map<String, GraphRegisteredEntity> staged = new LinkedHashMap<>();
            Map<String, String> stagedNames = new HashMap<>();
            Set<String> existingIds = new LinkedHashSet<>();
            // 先在内存中完成整批校验和合并，任何冲突都发生在真正写表之前。
            for (GraphRegisteredEntity value : values) {
                GraphRegisteredEntity existing = staged.get(value.getNodeId());
                if (existing == null) {
                    existing = load(connection, space, value.getNodeId(), true);
                    if (existing != null) existingIds.add(value.getNodeId());
                }
                GraphRegisteredEntity merged = merge(existing, value);
                assertNamesAvailable(connection, space, merged);
                stageNames(stagedNames, merged);
                staged.put(merged.getNodeId(), merged);
            }
            for (GraphRegisteredEntity entity : staged.values()) {
                upsert(connection, space, entity, existingIds.contains(entity.getNodeId()));
            }
            connection.commit();
        } catch (SQLException error) {
            rollback(connection);
            throw error;
        } catch (RuntimeException error) {
            rollback(connection);
            throw error;
        } finally {
            close(connection);
        }
    }

    /**
     * 按 nodeId 读取实体；写路径通过 lock=true 请求行锁，读路径无需锁定。
     */
    private GraphRegisteredEntity load(Connection connection, String space, String id, boolean lock)
        throws SQLException {
        String sql = "SELECT entity_type,canonical_name,aliases_json,properties_json FROM "
            + table("entities") + " WHERE space_name=? AND node_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, space);
            statement.setString(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? read(id, rows) : null;
            }
        }
    }

    /**
     * 将主体表中的 JSON 别名、属性和投影列组装为不可变实体。
     */
    private static GraphRegisteredEntity read(String id, ResultSet rows) throws SQLException {
        List<String> aliases = JSON.parseArray(rows.getString(3), String.class);
        Map<String, Object> properties = JSON.parseObject(rows.getString(4));
        return new GraphRegisteredEntity(id, rows.getString(1), rows.getString(2), aliases, properties);
    }

    /**
     * 确认实体的每个规范化名称尚未归属于其他 nodeId。
     *
     * <p>数据库唯一键仍是最终并发防线；此处负责提供更清晰的业务冲突异常。</p>
     */
    private void assertNamesAvailable(Connection connection, String space, GraphRegisteredEntity entity)
        throws SQLException {
        String sql = "SELECT node_id FROM " + table("entity_names")
            + " WHERE space_name=? AND entity_type=? AND name_hash=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String name : names(entity)) {
                statement.setString(1, space);
                statement.setString(2, normalize(entity.getType()));
                statement.setString(3, sha256(normalize(name)));
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && !entity.getNodeId().equals(rows.getString(1))) {
                        throw conflict(name);
                    }
                }
            }
        }
    }

    /**
     * 写入实体主体并原子重建该节点的全部名称索引。
     *
     * <p>调用方已确认行是否存在：已存在时必须精确更新一行；不存在时直接 INSERT，使并发首次
     * 创建必然通过唯一键显式竞争，并由外层重试合并。</p>
     */
    private void upsert(Connection connection, String space, GraphRegisteredEntity entity, boolean existing)
        throws SQLException {
        if (existing) {
            String update = "UPDATE " + table("entities")
                + " SET entity_type=?,canonical_name=?,aliases_json=?,properties_json=? WHERE space_name=? AND node_id=?";
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                bindEntity(statement, entity, space);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Registered entity disappeared while locked: " + entity.getNodeId());
                }
            }
        } else {
            String insert = "INSERT INTO " + table("entities")
                + " (space_name,node_id,entity_type,canonical_name,aliases_json,properties_json) VALUES (?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, space);
                statement.setString(2, entity.getNodeId());
                statement.setString(3, entity.getType());
                statement.setString(4, entity.getCanonicalName());
                statement.setString(5, JSON.toJSONString(entity.getAliases()));
                statement.setString(6, JSON.toJSONString(entity.getProperties()));
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + table("entity_names") + " WHERE space_name=? AND node_id=?")) {
            statement.setString(1, space);
            statement.setString(2, entity.getNodeId());
            statement.executeUpdate();
        }
        String insertName = "INSERT INTO " + table("entity_names")
            + " (space_name,entity_type,name_hash,normalized_name,node_id) VALUES (?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(insertName)) {
            for (String name : names(entity)) {
                String normalized = normalize(name);
                statement.setString(1, space);
                statement.setString(2, normalize(entity.getType()));
                statement.setString(3, sha256(normalized));
                statement.setString(4, normalized);
                statement.setString(5, entity.getNodeId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 绑定主体表 UPDATE 的实体字段和定位键。
     */
    private static void bindEntity(PreparedStatement statement, GraphRegisteredEntity entity, String space)
        throws SQLException {
        statement.setString(1, entity.getType());
        statement.setString(2, entity.getCanonicalName());
        statement.setString(3, JSON.toJSONString(entity.getAliases()));
        statement.setString(4, JSON.toJSONString(entity.getProperties()));
        statement.setString(5, space);
        statement.setString(6, entity.getNodeId());
    }

    /**
     * 在访问数据库前检查同一批次内不同节点的规范化名称冲突。
     */
    private static void stageNames(Map<String, String> stagedNames, GraphRegisteredEntity entity) {
        for (String name : names(entity)) {
            String key = normalize(entity.getType()) + "\u0000" + normalize(name);
            String owner = stagedNames.get(key);
            if (owner != null && !owner.equals(entity.getNodeId())) throw conflict(name);
            stagedNames.put(key, entity.getNodeId());
        }
    }

    /**
     * 合并同一节点的别名与属性；节点类型和首次确认的规范名称保持稳定。
     */
    private static GraphRegisteredEntity merge(GraphRegisteredEntity existing,
                                               GraphRegisteredEntity incoming) {
        if (existing == null) return incoming;
        if (!existing.getType().equals(incoming.getType())) {
            throw new GraphExtractionException("Registered node type cannot change: " + incoming.getNodeId());
        }
        Set<String> aliases = new LinkedHashSet<>(existing.getAliases());
        aliases.addAll(incoming.getAliases());
        Map<String, Object> properties = new LinkedHashMap<>(existing.getProperties());
        properties.putAll(incoming.getProperties());
        return new GraphRegisteredEntity(existing.getNodeId(), existing.getType(), existing.getCanonicalName(),
            new ArrayList<>(aliases), properties);
    }

    /**
     * 返回规范名称及别名，并按规范化结果去重，避免同一实体自身触发名称唯一键冲突。
     */
    private static List<String> names(GraphRegisteredEntity entity) {
        Map<String, String> unique = new LinkedHashMap<>();
        unique.put(normalize(entity.getCanonicalName()), entity.getCanonicalName());
        for (String alias : entity.getAliases()) unique.putIfAbsent(normalize(alias), alias);
        return new ArrayList<>(unique.values());
    }

    /**
     * 对名称执行 Unicode NFKC、首尾裁剪、连续空白折叠及 Locale.ROOT 小写转换。
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 构造名称已归属于其他节点的领域异常。
     */
    private static GraphExtractionException conflict(String name) {
        return new GraphExtractionException("Entity registry name is already assigned to another node: " + name);
    }

    /**
     * 判断异常是否属于可通过重新读取解决的数据库完整性约束竞争。
     */
    private static boolean isConstraint(SQLException error) {
        String state = error.getSQLState();
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
