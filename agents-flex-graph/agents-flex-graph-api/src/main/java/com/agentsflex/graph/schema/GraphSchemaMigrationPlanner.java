package com.agentsflex.graph.schema;

import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaComparator;
import com.agentsflex.graph.schema.GraphSchemaDiff;
import com.agentsflex.graph.schema.GraphSchemaMigrationPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据 Schema 差异生成面向审批和 UI 展示的迁移计划。
 */
public final class GraphSchemaMigrationPlanner {
    private GraphSchemaMigrationPlanner() {
    }

    /**
     * 生成迁移计划；执行顺序为新增、定义变化、删除。
     */
    public static GraphSchemaMigrationPlan plan(GraphSchema expected, GraphSchema actual) {
        GraphSchemaDiff diff = GraphSchemaComparator.compare(expected, actual);
        List<GraphSchemaMigrationPlan.Step> steps = new ArrayList<>();
        for (String addition : diff.getAdditions()) {
            steps.add(new GraphSchemaMigrationPlan.Step("ADD " + addition,
                GraphSchemaMigrationPlan.Risk.ADDITIVE));
        }
        for (String change : diff.getChanges()) {
            steps.add(new GraphSchemaMigrationPlan.Step("CHANGE " + change,
                GraphSchemaMigrationPlan.Risk.REVIEW_REQUIRED));
        }
        for (String removal : diff.getRemovals()) {
            steps.add(new GraphSchemaMigrationPlan.Step("REMOVE " + removal,
                GraphSchemaMigrationPlan.Risk.DESTRUCTIVE));
        }
        GraphSchemaMigrationPlan.Risk risk = GraphSchemaMigrationPlan.Risk.NONE;
        if (!diff.getAdditions().isEmpty()) risk = GraphSchemaMigrationPlan.Risk.ADDITIVE;
        if (!diff.getChanges().isEmpty()) risk = GraphSchemaMigrationPlan.Risk.REVIEW_REQUIRED;
        if (!diff.getRemovals().isEmpty()) risk = GraphSchemaMigrationPlan.Risk.DESTRUCTIVE;
        return new GraphSchemaMigrationPlan(risk, steps);
    }
}
