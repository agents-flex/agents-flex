package com.agentsflex.graph.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 比较期望 Schema 与实际 Schema，生成稳定、可审计的差异项。
 */
public final class GraphSchemaComparator {
    private GraphSchemaComparator() {
    }

    /**
     * 比较两个 Schema。
     *
     * @param expected 期望状态
     * @param actual   当前状态
     * @return 差异结果
     */
    public static GraphSchemaDiff compare(GraphSchema expected, GraphSchema actual) {
        if (expected == null || actual == null) {
            throw new IllegalArgumentException("expected and actual schema must not be null");
        }
        java.util.ArrayList<String> additions = new java.util.ArrayList<>();
        java.util.ArrayList<String> removals = new java.util.ArrayList<>();
        java.util.ArrayList<String> changes = new java.util.ArrayList<>();

        compareNodes(expected.getNodeTypes(), actual.getNodeTypes(), additions, removals, changes);
        compareEdges(expected.getEdgeTypes(), actual.getEdgeTypes(), additions, removals, changes);
        compareIndexes(expected.getIndexes(), actual.getIndexes(), additions, removals, changes);
        return new GraphSchemaDiff(additions, removals, changes);
    }

    private static void compareNodes(List<GraphSchema.NodeType> expected, List<GraphSchema.NodeType> actual,
                                     List<String> additions, List<String> removals, List<String> changes) {
        Map<String, GraphSchema.NodeType> wanted = new LinkedHashMap<>();
        Map<String, GraphSchema.NodeType> current = new LinkedHashMap<>();
        for (GraphSchema.NodeType node : expected) wanted.put(node.getLabel(), node);
        for (GraphSchema.NodeType node : actual) current.put(node.getLabel(), node);
        compareKeys("node", wanted, current, additions, removals);
        for (String label : wanted.keySet()) {
            if (!current.containsKey(label)) continue;
            compareProperties("node:" + label, wanted.get(label).getProperties(), current.get(label).getProperties(),
                additions, removals, changes);
        }
    }

    private static void compareEdges(List<GraphSchema.EdgeType> expected, List<GraphSchema.EdgeType> actual,
                                     List<String> additions, List<String> removals, List<String> changes) {
        Map<String, GraphSchema.EdgeType> wanted = new LinkedHashMap<>();
        Map<String, GraphSchema.EdgeType> current = new LinkedHashMap<>();
        for (GraphSchema.EdgeType edge : expected) wanted.put(edge.getType(), edge);
        for (GraphSchema.EdgeType edge : actual) current.put(edge.getType(), edge);
        compareKeys("edge", wanted, current, additions, removals);
        for (String type : wanted.keySet()) {
            if (!current.containsKey(type)) continue;
            GraphSchema.EdgeType want = wanted.get(type);
            GraphSchema.EdgeType have = current.get(type);
            if (!Objects.equals(want.getSourceLabel(), have.getSourceLabel())
                || !Objects.equals(want.getTargetLabel(), have.getTargetLabel())) {
                changes.add("edge:" + type + ".endpoints " + want.getSourceLabel() + "->" + want.getTargetLabel()
                    + " -> " + have.getSourceLabel() + "->" + have.getTargetLabel());
            }
            compareProperties("edge:" + type, want.getProperties(), have.getProperties(),
                additions, removals, changes);
        }
    }

    private static void compareIndexes(List<GraphSchema.Index> expected, List<GraphSchema.Index> actual,
                                       List<String> additions, List<String> removals, List<String> changes) {
        Map<String, GraphSchema.Index> wanted = new LinkedHashMap<>();
        Map<String, GraphSchema.Index> current = new LinkedHashMap<>();
        for (GraphSchema.Index index : expected) wanted.put(index.getName(), index);
        for (GraphSchema.Index index : actual) current.put(index.getName(), index);
        compareKeys("index", wanted, current, additions, removals);
        for (String name : wanted.keySet()) {
            if (!current.containsKey(name)) continue;
            GraphSchema.Index want = wanted.get(name);
            GraphSchema.Index have = current.get(name);
            if (want.getTarget() != have.getTarget() || !want.getTypeName().equals(have.getTypeName())
                || !want.getProperties().equals(have.getProperties()) || want.isUnique() != have.isUnique()) {
                changes.add("index:" + name + " definition changed");
            }
        }
    }

    private static <T> void compareKeys(String prefix, Map<String, T> expected, Map<String, T> actual,
                                        List<String> additions, List<String> removals) {
        for (String key : expected.keySet()) if (!actual.containsKey(key)) additions.add(prefix + ":" + key);
        for (String key : actual.keySet()) if (!expected.containsKey(key)) removals.add(prefix + ":" + key);
    }

    private static void compareProperties(String prefix, List<GraphSchema.Property> expected,
                                          List<GraphSchema.Property> actual, List<String> additions,
                                          List<String> removals, List<String> changes) {
        Map<String, GraphSchema.Property> wanted = new LinkedHashMap<>();
        Map<String, GraphSchema.Property> current = new LinkedHashMap<>();
        for (GraphSchema.Property property : expected) wanted.put(property.getName(), property);
        for (GraphSchema.Property property : actual) current.put(property.getName(), property);
        compareKeys(prefix + ".property", wanted, current, additions, removals);
        for (String name : wanted.keySet()) {
            if (!current.containsKey(name)) continue;
            GraphSchema.Property want = wanted.get(name);
            GraphSchema.Property have = current.get(name);
            if (want.getType() != have.getType() || want.isRequired() != have.isRequired()) {
                changes.add(prefix + ".property:" + name + " definition changed");
            }
        }
    }
}
