package com.agentsflex.graph.nebula.manager;

import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.schema.GraphSchemaValidation;
import com.agentsflex.graph.schema.GraphSchemaApplyResult;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.nebula.NebulaGraphStore;
import com.agentsflex.graph.nebula.NebulaGraphStoreConfig;

import com.vesoft.nebula.client.graph.data.ResultSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 使用 nGQL 管理 Nebula 图空间、TAG、EDGE 和索引。
 */
public final class NebulaGraphManager implements GraphManager {
    /**
     * 所属存储，用于获取空间会话池。
     */
    private final NebulaGraphStore store;
    /**
     * Nebula 配置。
     */
    private final NebulaGraphStoreConfig config;

    /**
     * 创建空间管理器。
     */
    public NebulaGraphManager(NebulaGraphStore store, NebulaGraphStoreConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * 创建 Nebula 图空间。
     */
    @Override
    public void createSpace(GraphSpaceDefinition definition, CreateMode mode) {
        if (mode == CreateMode.VALIDATE_ONLY) {
            spaceExists(definition.getName());
            return;
        }
        String statement = "CREATE SPACE " + (mode == CreateMode.IF_ABSENT ? "IF NOT EXISTS " : "") + definition.getName()
            + "(partition_num = " + definition.getPartitionCount() + ", replica_factor = "
            + definition.getReplicaFactor() + ", vid_type = fixed_string(64));";
        execute("", statement);
    }

    /**
     * 通过 SHOW SPACES 查询空间是否存在。
     */
    @Override
    public boolean spaceExists(String name) {
        GraphIdentifiers.requireValid(name, "space");
        try {
            return listSpaces().contains(name);
        } catch (Exception e) {
            throw failure(GraphErrorCode.CONNECTION_FAILED, "Unable to list Nebula spaces", e);
        }
    }

    /**
     * 列出 Nebula 图空间。
     */
    @Override
    public List<String> listSpaces() {
        try {
            ResultSet result = store.pool("").execute("SHOW SPACES");
            if (!result.isSucceeded()) throw new GraphException(GraphErrorCode.CONNECTION_FAILED,
                result.getErrorMessage());
            List<String> spaces = new ArrayList<>();
            // ValueWrapper.toString() 对字符串会保留 Nebula 的引号，必须取其真实字符串值，
            // 否则 spaceExists 会把 SHOW SPACES 的结果误判为不存在。
            for (int i = 0; i < result.rowsSize(); i++) spaces.add(text(result.rowValues(i).get(0)));
            return spaces;
        } catch (Exception e) {
            throw failure(GraphErrorCode.CONNECTION_FAILED, "Unable to list Nebula spaces", e);
        }
    }

    /**
     * 删除 Nebula 图空间。
     */
    @Override
    public void dropSpace(String name) {
        GraphIdentifiers.requireValid(name, "space");
        execute("", "DROP SPACE " + name);
    }

    /**
     * 将 Schema 编译为 TAG、EDGE 和索引 DDL。
     */
    @Override
    public void applySchema(String space, GraphSchema schema, SchemaMode mode) {
        GraphSchemaValidation validation = validateSchema(space, schema);
        if (!validation.isValid()) throw new GraphException(GraphErrorCode.SCHEMA_VALIDATION_FAILED,
            validation.getErrors().toString());
        if (mode == SchemaMode.VALIDATE_ONLY) return;
        for (GraphSchema.NodeType node : schema.getNodeTypes()) {
            StringBuilder statement = new StringBuilder("CREATE TAG IF NOT EXISTS ").append(node.getLabel()).append(" (");
            for (int i = 0; i < node.getProperties().size(); i++) {
                GraphSchema.Property property = node.getProperties().get(i);
                if (i > 0) statement.append(", ");
                statement.append(property.getName()).append(" ").append(type(property.getType()));
            }
            statement.append(");");
            execute(space, statement.toString());
        }
        for (GraphSchema.EdgeType edge : schema.getEdgeTypes()) {
            StringBuilder statement = new StringBuilder("CREATE EDGE IF NOT EXISTS ").append(edge.getType()).append(" (");
            for (int i = 0; i < edge.getProperties().size(); i++) {
                GraphSchema.Property property = edge.getProperties().get(i);
                if (i > 0) statement.append(", ");
                statement.append(property.getName()).append(" ").append(type(property.getType()));
            }
            statement.append(");");
            execute(space, statement.toString());
        }
        for (GraphSchema.Index index : schema.getIndexes()) {
            if (index.isUnique()) {
                throw new com.agentsflex.graph.UnsupportedGraphFeatureException("Nebula Graph does not support unique indexes in the portable schema");
            }
            String kind = index.getTarget() == GraphSchema.IndexTarget.NODE ? "TAG" : "EDGE";
            StringBuilder statement = new StringBuilder("CREATE ").append(kind).append(" INDEX IF NOT EXISTS ")
                .append(index.getName()).append(" ON ").append(index.getTypeName()).append("(");
            for (int i = 0; i < index.getProperties().size(); i++) {
                if (i > 0) statement.append(", ");
                statement.append(index.getProperties().get(i));
            }
            statement.append(");");
            execute(space, statement.toString());
        }
    }

    /**
     * 应用 Schema 并返回适合开发工具展示的步骤和后端警告。
     */
    @Override
    public GraphSchemaApplyResult applySchemaResult(String space, GraphSchema schema, SchemaMode mode) {
        long started = System.currentTimeMillis();
        GraphSchemaValidation validation = validateSchema(space, schema);
        if (!validation.isValid()) {
            return GraphSchemaApplyResult.failure(new GraphException(GraphErrorCode.SCHEMA_VALIDATION_FAILED,
                validation.getErrors().toString()), System.currentTimeMillis() - started);
        }
        List<String> steps = new ArrayList<>();
        for (GraphSchema.NodeType node : schema.getNodeTypes()) steps.add("ensure tag " + node.getLabel());
        for (GraphSchema.EdgeType edge : schema.getEdgeTypes()) steps.add("ensure edge " + edge.getType());
        for (GraphSchema.Index index : schema.getIndexes()) steps.add("ensure index " + index.getName());
        try {
            SchemaMode resolvedMode = mode == null ? SchemaMode.ADDITIVE : mode;
            applySchema(space, schema, resolvedMode);
            return GraphSchemaApplyResult.success(resolvedMode == SchemaMode.VALIDATE_ONLY
                    ? java.util.Collections.<String>emptyList() : steps, validation.getWarnings(),
                System.currentTimeMillis() - started);
        } catch (RuntimeException error) {
            return GraphSchemaApplyResult.failure(error, System.currentTimeMillis() - started);
        }
    }

    /**
     * 校验空间名和 Schema 非空；更细的能力差异在应用阶段报告。
     */
    @Override
    public GraphSchemaValidation validateSchema(String space, GraphSchema schema) {
        GraphIdentifiers.requireValid(space, "space");
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (GraphSchema.Index index : schema.getIndexes()) {
            if (index.isUnique()) {
                errors.add("Nebula portable Schema does not support unique indexes: " + index.getName());
            }
        }
        if (!schema.getEdgeTypes().isEmpty()) {
            warnings.add("Nebula edge endpoint labels are not enforced by portable Schema");
        }
        return new GraphSchemaValidation(errors, warnings);
    }

    /**
     * 执行管理语句并将 Nebula 错误转换为统一异常。
     */
    private void execute(String space, String statement) {
        try {
            ResultSet result = store.pool(space).execute(statement);
            if (!result.isSucceeded()) throw new GraphException(GraphErrorCode.SCHEMA_APPLY_FAILED,
                result.getErrorMessage());
        } catch (Exception e) {
            throw failure(GraphErrorCode.SCHEMA_APPLY_FAILED, "Nebula management operation failed", e);
        }
    }

    /**
     * 将可移植属性类型映射为 Nebula 类型。
     */
    private String type(GraphSchema.PropertyType type) {
        switch (type) {
            case BOOLEAN:
                return "bool";
            case INT64:
                return "int";
            case DOUBLE:
                return "double";
            case DATE:
                return "date";
            case DATETIME:
                return "datetime";
            default:
                return "string";
        }
    }

    /**
     * 从 SHOW/DESCRIBE 语句读取 Nebula TAG 和 EDGE 定义。
     */
    @Override
    public GraphSchemaInspection inspectSchema(String space) {
        GraphIdentifiers.requireValid(space, "space");
        GraphSchema.Builder schema = GraphSchema.builder();
        List<String> warnings = new ArrayList<>();
        List<String> unsupportedMetadata = new ArrayList<>();
        try {
            ResultSet tags = store.pool(space).execute("SHOW TAGS");
            ensureSucceeded(tags);
            for (int i = 0; i < tags.rowsSize(); i++) {
                String tag = text(tags.rowValues(i).get(0));
                if (!GraphIdentifiers.isValid(tag)) {
                    warnings.add("Skipped non-portable Nebula TAG: " + tag);
                    continue;
                }
                schema.nodeType(GraphSchema.NodeType.of(tag, describeProperties(space, "TAG", tag, warnings)));
            }
            ResultSet edges = store.pool(space).execute("SHOW EDGES");
            ensureSucceeded(edges);
            for (int i = 0; i < edges.rowsSize(); i++) {
                String edge = text(edges.rowValues(i).get(0));
                if (!GraphIdentifiers.isValid(edge)) {
                    warnings.add("Skipped non-portable Nebula EDGE: " + edge);
                    continue;
                }
                schema.edgeType(GraphSchema.EdgeType.any(edge, describeProperties(space, "EDGE", edge, warnings)));
            }
        } catch (Exception e) {
            if (e instanceof GraphException) throw (GraphException) e;
            throw new GraphException(GraphErrorCode.CONNECTION_FAILED,
                "Unable to inspect Nebula schema: " + e.getMessage(), e);
        }
        warnings.add("Nebula edge schemas do not constrain source and target tags");
        warnings.add("Nebula index metadata is not included in the portable inspection result");
        unsupportedMetadata.add("edge.endpointLabels");
        unsupportedMetadata.add("indexes");
        return new GraphSchemaInspection(schema.build(), false, warnings, unsupportedMetadata,
            "", System.currentTimeMillis());
    }

    private GraphSchema.Property[] describeProperties(String space, String kind, String name,
                                                      List<String> warnings) throws Exception {
        ResultSet result = store.pool(space).execute("DESCRIBE " + kind + " " + name);
        ensureSucceeded(result);
        List<GraphSchema.Property> properties = new ArrayList<>();
        for (int i = 0; i < result.rowsSize(); i++) {
            ResultSet.Record row = result.rowValues(i);
            String field = text(row.get(0));
            String type = text(row.get(1));
            if (!GraphIdentifiers.isValid(field)) {
                warnings.add("Skipped non-portable Nebula " + kind + " property: " + field);
                continue;
            }
            boolean required = row.size() > 2 && "NO".equalsIgnoreCase(text(row.get(2)));
            properties.add(new GraphSchema.Property(field, propertyType(type), required));
        }
        return properties.toArray(new GraphSchema.Property[0]);
    }

    private void ensureSucceeded(ResultSet result) {
        if (!result.isSucceeded()) throw new GraphException(GraphErrorCode.SCHEMA_APPLY_FAILED,
            result.getErrorMessage());
    }

    private String text(com.vesoft.nebula.client.graph.data.ValueWrapper value) throws Exception {
        return value.isString() ? value.asString() : value.toString();
    }

    private GraphSchema.PropertyType propertyType(String value) {
        String type = value == null ? "" : value.toLowerCase();
        if (type.contains("bool")) return GraphSchema.PropertyType.BOOLEAN;
        if (type.contains("int")) return GraphSchema.PropertyType.INT64;
        if (type.contains("double") || type.contains("float")) return GraphSchema.PropertyType.DOUBLE;
        if (type.equals("date")) return GraphSchema.PropertyType.DATE;
        if (type.contains("datetime") || type.contains("timestamp")) return GraphSchema.PropertyType.DATETIME;
        return GraphSchema.PropertyType.STRING;
    }

    /**
     * 保留已有 GraphException，统一包装底层管理异常。
     */
    private GraphException failure(GraphErrorCode code, String message, Exception error) {
        if (error instanceof GraphException) return (GraphException) error;
        return new GraphException(code, message + ": " + error.getMessage(), error);
    }
}
