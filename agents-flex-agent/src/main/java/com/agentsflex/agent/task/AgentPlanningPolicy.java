/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 控制模型是否可以在普通运行循环中创建任务计划。
 *
 * <p>启用后，Runner 会向模型提供内置规划工具。是否使用该工具由模型根据当前消息自行判断，
 * 调用方不需要选择另一套规划执行入口。</p>
 */
public final class AgentPlanningPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 子任务失败后的处理方式。
     */
    public enum FailureStrategy {
        /**
         * 停止调度后续任务，并让父 Agent 汇总已经取得的结果和失败原因。
         */
        STOP,
        /**
         * 记录失败；有重规划额度时先调整待执行任务，否则继续执行原计划。
         */
        CONTINUE
    }

    /**
     * 是否向模型开放规划能力。
     */
    private final boolean enabled;
    /**
     * 单个计划允许包含的任务总数。
     */
    private final int maxTasks;
    /**
     * 允许形成规划子 Turn 的最大嵌套深度。
     */
    private final int maxDepth;
    /**
     * 子 Turn 是否也可以调用规划工具。
     */
    private final boolean childPlanningAllowed;
    /**
     * 子任务失败后的计划处理方式。
     */
    private final FailureStrategy failureStrategy;
    /**
     * 追加到规划工具说明中的领域约束。
     */
    private final String planningInstructions;
    /**
     * 一个计划最多允许成功调整的次数。
     */
    private final int maxReplans;
    /**
     * 是否允许修改或移除尚未执行的任务。
     */
    private final boolean taskRevisionAllowed;
    /**
     * 是否允许在调整计划时添加新的任务 ID。
     */
    private final boolean taskAppendAllowed;
    /**
     * 写入父计划和父提示词的单个子任务结果正文上限，0 表示不限制。
     */
    private final int taskResultMaxLength;
    /**
     * 全部子任务完成后是否再次调用父模型生成汇总。
     */
    private final boolean finalSummaryRequired;
    /**
     * 允许规划模型选择的目标 Agent ID 白名单。
     */
    private final Set<String> allowedAgentIds;

    /**
     * 从已校验构建器冻结规划开关、委派白名单和重规划限制。
     */
    private AgentPlanningPolicy(Builder builder) {
        this.enabled = builder.enabled;
        this.maxTasks = builder.maxTasks;
        this.maxDepth = builder.maxDepth;
        this.childPlanningAllowed = builder.childPlanningAllowed;
        this.failureStrategy = builder.failureStrategy;
        this.planningInstructions = builder.planningInstructions;
        this.maxReplans = builder.maxReplans;
        this.taskRevisionAllowed = builder.taskRevisionAllowed;
        this.taskAppendAllowed = builder.taskAppendAllowed;
        this.taskResultMaxLength = builder.taskResultMaxLength;
        this.finalSummaryRequired = builder.finalSummaryRequired;
        this.allowedAgentIds = Collections.unmodifiableSet(
            new LinkedHashSet<>(builder.allowedAgentIds));
    }

    /**
     * 创建关闭任务规划的策略。
     */
    public static AgentPlanningPolicy disabled() {
        return builder().enabled(false).build();
    }

    /**
     * 创建启用任务规划的策略。
     */
    public static AgentPlanningPolicy enabled() {
        return builder().enabled(true).build();
    }

    /**
     * @return 使用保守默认值的新策略构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 是否向当前 Turn 的模型开放规划工具
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return 单个计划允许的任务总数上限
     */
    public int getMaxTasks() {
        return maxTasks;
    }

    /**
     * @return 允许创建规划子 Turn 的最大深度
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * @return 子 Turn 是否可以继续使用规划工具
     */
    public boolean isChildPlanningAllowed() {
        return childPlanningAllowed;
    }

    /**
     * @return 子任务失败后的处理策略
     */
    public FailureStrategy getFailureStrategy() {
        return failureStrategy;
    }

    /**
     * @return 附加给规划模型的领域约束
     */
    public String getPlanningInstructions() {
        return planningInstructions;
    }

    /**
     * @return 单个计划允许成功调整的最大次数
     */
    public int getMaxReplans() {
        return maxReplans;
    }

    /**
     * @return 是否允许修改或移除待执行任务
     */
    public boolean isTaskRevisionAllowed() {
        return taskRevisionAllowed;
    }

    /**
     * @return 是否允许追加新的任务 ID
     */
    public boolean isTaskAppendAllowed() {
        return taskAppendAllowed;
    }

    /**
     * @return 写回父上下文的子任务结果正文上限，0 表示不限
     */
    public int getTaskResultMaxLength() {
        return taskResultMaxLength;
    }

    /**
     * @return 子任务结束后是否必须由父模型生成最终汇总
     */
    public boolean isFinalSummaryRequired() {
        return finalSummaryRequired;
    }

    /**
     * @return 不可修改的委派目标 Agent ID 白名单
     */
    public Set<String> getAllowedAgentIds() {
        return allowedAgentIds;
    }

    /**
     * 判断一个任务是否可以委派给指定 Agent。
     *
     * <p>当前 Agent 始终可用；其他 Agent 必须显式加入允许列表，避免模型把任务委派给
     * 未经授权的定义。</p>
     */
    public boolean canDelegateTo(String currentAgentId, String targetAgentId) {
        return !StringUtil.hasText(targetAgentId)
            || targetAgentId.equals(currentAgentId)
            || allowedAgentIds.contains(targetAgentId);
    }

    /**
     * 任务规划策略构建器。
     */
    public static final class Builder {
        private boolean enabled;
        private int maxTasks = 10;
        private int maxDepth = 1;
        private boolean childPlanningAllowed;
        private FailureStrategy failureStrategy = FailureStrategy.STOP;
        private String planningInstructions;
        private int maxReplans;
        private boolean taskRevisionAllowed;
        private boolean taskAppendAllowed;
        private int taskResultMaxLength;
        private boolean finalSummaryRequired = true;
        private final Set<String> allowedAgentIds = new LinkedHashSet<>();

        /**
         * 设置是否开放任务规划。
         */
        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        /**
         * 设置单个计划的任务总数上限。
         */
        public Builder maxTasks(int value) {
            maxTasks = value;
            return this;
        }

        /**
         * 设置规划父子 Turn 的最大嵌套深度。
         */
        public Builder maxDepth(int value) {
            maxDepth = value;
            return this;
        }

        /**
         * 设置子 Turn 是否可以继续自主规划。
         */
        public Builder childPlanningAllowed(boolean value) {
            childPlanningAllowed = value;
            return this;
        }

        /**
         * 设置子任务失败后的停止或继续策略。
         */
        public Builder failureStrategy(FailureStrategy value) {
            failureStrategy = value;
            return this;
        }

        /**
         * 设置附加给模型的领域规划要求。
         */
        public Builder planningInstructions(String value) {
            planningInstructions = value;
            return this;
        }

        /**
         * 设置单个计划允许成功调整的最大次数。
         */
        public Builder maxReplans(int value) {
            maxReplans = value;
            return this;
        }

        /**
         * 设置是否允许修改或移除尚未执行的任务。
         */
        public Builder taskRevisionAllowed(boolean value) {
            taskRevisionAllowed = value;
            return this;
        }

        /**
         * 设置是否允许在重规划时追加新任务。
         */
        public Builder taskAppendAllowed(boolean value) {
            taskAppendAllowed = value;
            return this;
        }

        /**
         * 设置写入父计划和父 Prompt 的结果正文长度上限。
         */
        public Builder taskResultMaxLength(int value) {
            taskResultMaxLength = value;
            return this;
        }

        /**
         * 设置是否额外调用父模型汇总全部子任务结果。
         */
        public Builder finalSummaryRequired(boolean value) {
            finalSummaryRequired = value;
            return this;
        }

        /**
         * 添加一个允许模型选择的目标 Agent ID。
         */
        public Builder allowAgent(String agentId) {
            if (!StringUtil.hasText(agentId)) {
                throw new IllegalArgumentException("agentId must not be blank");
            }
            allowedAgentIds.add(agentId);
            return this;
        }

        /**
         * 批量替换式添加允许委派的 Agent ID。
         */
        public Builder allowedAgentIds(Set<String> values) {
            if (values != null) for (String value : values) allowAgent(value);
            return this;
        }

        /**
         * 校验数值范围和重规划配置组合后创建不可变策略。
         */
        public AgentPlanningPolicy build() {
            if (maxTasks <= 0 || maxDepth <= 0 || maxReplans < 0
                || taskResultMaxLength < 0) {
                throw new IllegalStateException(
                    "maxTasks and maxDepth must be greater than 0; "
                        + "maxReplans and taskResultMaxLength must not be negative");
            }
            if (maxReplans > 0 && !taskRevisionAllowed && !taskAppendAllowed) {
                throw new IllegalStateException(
                    "maxReplans requires task revision or append to be enabled");
            }
            if (failureStrategy == null) {
                throw new IllegalStateException("failureStrategy must not be null");
            }
            return new AgentPlanningPolicy(this);
        }
    }
}
