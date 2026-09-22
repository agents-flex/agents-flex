package com.agentsflex.graph.neo4j.manager;

import com.agentsflex.graph.UnsupportedGraphFeatureException;

import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaValidation;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 Neo4j system 数据库和 Cypher 管理空间、Schema。
 */
public final class Neo4jGraphManager implements GraphManager {
    /**
     * 官方驱动。
     */
    private final Driver driver;
    /**
     * 存储配置。
     */
    private final Neo4jGraphStoreConfig config;

    /**
     * 创建管理器。
     */
    public Neo4jGraphManager(Driver driver, Neo4jGraphStoreConfig config) {
        this.driver = driver;
        this.config = config;
    }

    /**
     * 创建 Neo4j 数据库；Neo4j 以 database 映射逻辑图空间。
     */
    @Override
    public void createSpace(GraphSpaceDefinition definition, CreateMode mode) {
        if (mode == null) mode = CreateMode.IF_ABSENT;
        if (mode == CreateMode.VALIDATE_ONLY) {
            spaceExists(definition.getName());
            return;
        }
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase("system"))) {
            if (mode == CreateMode.FAIL_IF_EXISTS && spaceExists(definition.getName())) {
                throw new IllegalStateException("Neo4j database already exists: " + definition.getName());
            }
            if (mode == CreateMode.IF_ABSENT) {
                session.run("CREATE DATABASE " + definition.getName() + " IF NOT EXISTS").consume();
            } else {
                session.run("CREATE DATABASE " + definition.getName()).consume();
            }
        }
    }

    /**
     * 查询 system 数据库确认空间是否存在。
     */
    @Override
    public boolean spaceExists(String name) {
        GraphIdentifiers.requireValid(name, "space");
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase("system"))) {
            for (Record record : session.run("SHOW DATABASES YIELD name WHERE name = $name RETURN name",
                Collections.<String, Object>singletonMap("name", name)).list())
                return true;
            return false;
        }
    }

    /**
     * 列出 Neo4j 中可见数据库。
     */
    @Override
    public List<String> listSpaces() {
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase("system"))) {
            List<String> result = new ArrayList<>();
            for (Record record : session.run("SHOW DATABASES YIELD name RETURN name ORDER BY name").list()) {
                result.add(record.get("name").asString());
            }
            return result;
        }
    }

    /**
     * 删除指定 Neo4j 数据库。
     */
    @Override
    public void dropSpace(String name) {
        GraphIdentifiers.requireValid(name, "space");
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase("system"))) {
            session.run("DROP DATABASE " + name + " IF EXISTS").consume();
        }
    }

    /**
     * 将可移植 Schema 编译为 Neo4j 约束和索引。
     */
    @Override
    public void applySchema(String space, GraphSchema schema, SchemaMode mode) {
        GraphSchemaValidation validation = validateSchema(space, schema);
        if (!validation.isValid()) throw new IllegalArgumentException(validation.getErrors().toString());
        if (mode == SchemaMode.VALIDATE_ONLY) return;
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(space))) {
            for (GraphSchema.NodeType node : schema.getNodeTypes()) {
                String constraint = "agentsflex_" + node.getLabel() + "_id";
                String statement = "CREATE CONSTRAINT " + constraint + " IF NOT EXISTS FOR (n:" + node.getLabel()
                    + ") REQUIRE n.__agentsflex_id IS UNIQUE";
                session.run(statement).consume();
                for (GraphSchema.Property property : node.getProperties()) {
                    String index = "agentsflex_" + node.getLabel() + "_" + property.getName();
                    session.run("CREATE INDEX " + index + " IF NOT EXISTS FOR (n:" + node.getLabel() + ") ON (n."
                        + property.getName() + ")").consume();
                }
            }
            for (GraphSchema.Index index : schema.getIndexes()) {
                String alias = index.getTarget() == GraphSchema.IndexTarget.NODE ? "n" : "r";
                String target = alias + ":" + index.getTypeName();
                String properties = indexedProperties(alias, index.getProperties());
                if (index.isUnique()) {
                    if (index.getTarget() == GraphSchema.IndexTarget.EDGE) {
                        throw new com.agentsflex.graph.UnsupportedGraphFeatureException(
                            "Portable Neo4j adapter does not create relationship uniqueness constraints");
                    }
                    session.run("CREATE CONSTRAINT " + index.getName() + " IF NOT EXISTS FOR (" + target
                        + ") REQUIRE (" + properties + ") IS UNIQUE").consume();
                } else {
                    session.run("CREATE INDEX " + index.getName() + " IF NOT EXISTS FOR (" + target
                        + ") ON (" + properties + ")").consume();
                }
            }
        }
    }

    /**
     * 校验 Schema 的基本参数；Neo4j 具体差异在应用阶段由 Cypher 返回。
     */
    @Override
    public GraphSchemaValidation validateSchema(String space, GraphSchema schema) {
        GraphIdentifiers.requireValid(space, "space");
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        return GraphSchemaValidation.valid();
    }

    /**
     * 从 Neo4j 系统过程和 SHOW INDEXES 读取当前 Schema。
     */
    @Override
    public GraphSchemaInspection inspectSchema(String space) {
        GraphIdentifiers.requireValid(space, "space");
        Map<String, Map<String, GraphSchema.Property>> nodes = new LinkedHashMap<>();
        Map<String, Map<String, GraphSchema.Property>> edges = new LinkedHashMap<>();
        List<GraphSchema.Index> indexes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(space))) {
            for (Record record : session.run("CALL db.labels() YIELD label RETURN label ORDER BY label").list()) {
                String label = record.get("label").asString();
                if (!GraphIdentifiers.isValid(label)) {
                    warnings.add("Skipped non-portable Neo4j node label: " + label);
                    continue;
                }
                nodes.put(label, new LinkedHashMap<String, GraphSchema.Property>());
            }
            for (Record record : session.run("CALL db.schema.nodeTypeProperties() "
                + "YIELD nodeLabels, propertyName, propertyTypes, mandatory "
                + "RETURN nodeLabels, propertyName, propertyTypes, mandatory").list()) {
                if (record.get("propertyName").isNull()) continue;
                String propertyName = record.get("propertyName").asString();
                if (!GraphIdentifiers.isValid(propertyName)) {
                    warnings.add("Skipped non-portable Neo4j node property: " + propertyName);
                    continue;
                }
                GraphSchema.Property property = new GraphSchema.Property(propertyName,
                    propertyType(record.get("propertyTypes").asList(value -> value.asString())),
                    record.get("mandatory").asBoolean(false));
                for (String label : record.get("nodeLabels").asList(value -> value.asString())) {
                    if (!GraphIdentifiers.isValid(label)) {
                        warnings.add("Skipped non-portable Neo4j node label: " + label);
                        continue;
                    }
                    Map<String, GraphSchema.Property> properties = nodes.get(label);
                    if (properties == null) {
                        properties = new LinkedHashMap<>();
                        nodes.put(label, properties);
                    }
                    properties.put(property.getName(), property);
                }
            }
            for (Record record : session.run("CALL db.relationshipTypes() YIELD relationshipType "
                + "RETURN relationshipType ORDER BY relationshipType").list()) {
                String type = normalizeRelationshipType(record.get("relationshipType").asString());
                if (type == null) {
                    warnings.add("Skipped non-portable Neo4j relationship type: "
                        + record.get("relationshipType").asString());
                    continue;
                }
                edges.put(type,
                    new LinkedHashMap<String, GraphSchema.Property>());
            }
            for (Record record : session.run("CALL db.schema.relTypeProperties() "
                + "YIELD relType, propertyName, propertyTypes, mandatory "
                + "RETURN relType, propertyName, propertyTypes, mandatory").list()) {
                if (record.get("propertyName").isNull()) continue;
                String type = normalizeRelationshipType(record.get("relType").asString());
                if (type == null) {
                    warnings.add("Skipped non-portable Neo4j relationship type: "
                        + record.get("relType").asString());
                    continue;
                }
                String propertyName = record.get("propertyName").asString();
                if (!GraphIdentifiers.isValid(propertyName)) {
                    warnings.add("Skipped non-portable Neo4j relationship property: " + propertyName);
                    continue;
                }
                Map<String, GraphSchema.Property> properties = edges.get(type);
                if (properties == null) {
                    properties = new LinkedHashMap<>();
                    edges.put(type, properties);
                }
                GraphSchema.Property property = new GraphSchema.Property(propertyName,
                    propertyType(record.get("propertyTypes").asList(value -> value.asString())),
                    record.get("mandatory").asBoolean(false));
                properties.put(property.getName(), property);
            }
            try {
                for (Record record : session.run("SHOW INDEXES YIELD name, entityType, labelsOrTypes, properties, uniqueness "
                    + "RETURN name, entityType, labelsOrTypes, properties, uniqueness ORDER BY name").list()) {
                    List<String> types = record.get("labelsOrTypes").asList(value -> value.asString());
                    List<String> properties = record.get("properties").asList(value -> value.asString());
                    if (types.isEmpty() || properties.isEmpty()) continue;
                    String indexName = record.get("name").asString();
                    if (!GraphIdentifiers.isValid(indexName) || !GraphIdentifiers.isValid(types.get(0))) {
                        warnings.add("Skipped non-portable Neo4j index: " + indexName);
                        continue;
                    }
                    boolean portableProperties = true;
                    java.util.HashSet<String> distinctProperties = new java.util.HashSet<>();
                    for (String property : properties) {
                        if (!GraphIdentifiers.isValid(property) || !distinctProperties.add(property)) {
                            portableProperties = false;
                            break;
                        }
                    }
                    if (!portableProperties) {
                        warnings.add("Skipped Neo4j index with non-portable property: " + indexName);
                        continue;
                    }
                    String entityType = record.get("entityType").isNull() ? "" : record.get("entityType").asString();
                    GraphSchema.IndexTarget target = "NODE".equals(entityType)
                        ? GraphSchema.IndexTarget.NODE : GraphSchema.IndexTarget.EDGE;
                    indexes.add(new GraphSchema.Index(indexName, target, types.get(0), properties,
                        "UNIQUE".equals(record.get("uniqueness").asString())));
                }
            } catch (RuntimeException indexError) {
                warnings.add("Unable to inspect Neo4j indexes: " + indexError.getMessage());
            }
        }

        GraphSchema.Builder schema = GraphSchema.builder();
        for (Map.Entry<String, Map<String, GraphSchema.Property>> node : nodes.entrySet()) {
            schema.nodeType(GraphSchema.NodeType.of(node.getKey(),
                node.getValue().values().toArray(new GraphSchema.Property[0])));
        }
        for (Map.Entry<String, Map<String, GraphSchema.Property>> edge : edges.entrySet()) {
            schema.edgeType(GraphSchema.EdgeType.any(edge.getKey(),
                edge.getValue().values().toArray(new GraphSchema.Property[0])));
        }
        for (GraphSchema.Index index : indexes) schema.index(index);
        warnings.add("Neo4j relationship endpoint labels are data patterns, not enforceable schema constraints");
        // 关系端点标签无法从 Neo4j 的通用关系类型元数据中反推出，因此该结果始终不是完整反查。
        return new GraphSchemaInspection(schema.build(), false, warnings);
    }

    private GraphSchema.PropertyType propertyType(List<String> types) {
        String type = types.isEmpty() ? "STRING" : types.get(0).toUpperCase();
        if (type.contains("BOOLEAN")) return GraphSchema.PropertyType.BOOLEAN;
        if (type.contains("INTEGER")) return GraphSchema.PropertyType.INT64;
        if (type.contains("FLOAT")) return GraphSchema.PropertyType.DOUBLE;
        if (type.equals("DATE")) return GraphSchema.PropertyType.DATE;
        if (type.contains("DATETIME") || type.contains("LOCAL DATETIME")) return GraphSchema.PropertyType.DATETIME;
        return GraphSchema.PropertyType.STRING;
    }

    private String normalizeRelationshipType(String value) {
        String result = value == null ? "" : value.replace(":`", "").replace("`", "").replace(":", "");
        return GraphIdentifiers.isValid(result) ? result : null;
    }

    private String indexedProperties(String alias, List<String> properties) {
        StringBuilder result = new StringBuilder();
        for (String property : properties) {
            if (result.length() > 0) result.append(", ");
            result.append(alias).append(".").append(property);
        }
        return result.toString();
    }
}
