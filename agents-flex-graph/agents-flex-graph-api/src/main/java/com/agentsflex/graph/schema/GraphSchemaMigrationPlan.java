package com.agentsflex.graph.schema;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Schema 迁移预览，包含有序步骤和整体风险等级。
 */
public final class GraphSchemaMigrationPlan {
    /**
     * 迁移风险等级。
     */
    public enum Risk {
        /**
         * 没有差异。
         */
        NONE,
        /**
         * 只包含新增定义。
         */
        ADDITIVE,
        /**
         * 包含定义变化，需要适配器或人工确认。
         */
        REVIEW_REQUIRED,
        /**
         * 包含删除定义，可能导致数据或查询不可用。
         */
        DESTRUCTIVE
    }

    /**
     * 单个迁移步骤。
     */
    public static final class Step {
        private final String description;
        private final Risk risk;

        Step(String description, Risk risk) {
            this.description = description;
            this.risk = risk;
        }

        /**
         * @return 步骤说明
         */
        public String getDescription() {
            return description;
        }

        /**
         * @return 步骤风险
         */
        public Risk getRisk() {
            return risk;
        }
    }

    private final Risk risk;
    private final List<Step> steps;

    GraphSchemaMigrationPlan(Risk risk, List<Step> steps) {
        this.risk = risk;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /**
     * @return 整体风险等级
     */
    public Risk getRisk() {
        return risk;
    }

    /**
     * @return 有序迁移步骤
     */
    public List<Step> getSteps() {
        return steps;
    }

    /**
     * @return 是否必须人工确认后才能执行
     */
    public boolean requiresApproval() {
        return risk == Risk.REVIEW_REQUIRED || risk == Risk.DESTRUCTIVE;
    }
}
