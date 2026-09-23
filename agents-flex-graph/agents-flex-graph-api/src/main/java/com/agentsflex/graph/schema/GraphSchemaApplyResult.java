package com.agentsflex.graph.schema;

import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.error.GraphErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Schema 应用操作的结构化结果。
 *
 * <p>适配器仍可通过原有 {@code void applySchema} 实现 DDL；该结果对象为开发者工具提供
 * dry-run/执行反馈的统一承载方式。</p>
 */
public final class GraphSchemaApplyResult {
    /**
     * Schema 应用或校验是否成功。
     */
    private final boolean success;
    /**
     * 已实际执行的 DDL 或逻辑步骤；dry-run 时为空。
     */
    private final List<String> appliedSteps;
    /**
     * 不阻止本次操作但需要上层关注的警告。
     */
    private final List<String> warnings;
    /**
     * 失败时的错误说明；成功时为空。
     */
    private final String error;
    /**
     * 失败时的稳定错误分类。
     */
    private final GraphErrorCode errorCode;
    /**
     * 操作耗时，单位为毫秒。
     */
    private final long executionTimeMillis;

    /**
     * 创建并冻结 Schema 应用结果。
     */
    private GraphSchemaApplyResult(boolean success, List<String> appliedSteps, List<String> warnings,
                                   String error, GraphErrorCode errorCode, long executionTimeMillis) {
        this.success = success;
        this.appliedSteps = immutable(appliedSteps);
        this.warnings = immutable(warnings);
        this.error = error == null ? "" : error;
        this.errorCode = errorCode == null ? GraphErrorCode.UNKNOWN : errorCode;
        this.executionTimeMillis = Math.max(0L, executionTimeMillis);
    }

    /**
     * 创建成功结果。
     *
     * @param appliedSteps 实际执行步骤；校验模式下通常为空
     * @param warnings     兼容性警告
     * @param elapsed      执行耗时
     * @return 成功结果
     */
    public static GraphSchemaApplyResult success(List<String> appliedSteps, List<String> warnings, long elapsed) {
        return new GraphSchemaApplyResult(true, appliedSteps, warnings, "", GraphErrorCode.UNKNOWN, elapsed);
    }

    /**
     * 从异常创建失败结果。
     *
     * @param error   失败异常，可以为 {@code null}
     * @param elapsed 执行耗时
     * @return 失败结果
     */
    public static GraphSchemaApplyResult failure(Throwable error, long elapsed) {
        String message = error == null ? "Schema apply failed" : error.getMessage();
        GraphErrorCode code = error instanceof GraphException ? ((GraphException) error).getCode()
            : GraphErrorCode.SCHEMA_APPLY_FAILED;
        return new GraphSchemaApplyResult(false, Collections.<String>emptyList(), Collections.<String>emptyList(),
            message, code, elapsed);
    }

    /**
     * @return 是否成功。
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return 实际执行步骤的只读列表。
     */
    public List<String> getAppliedSteps() {
        return appliedSteps;
    }

    /**
     * @return 警告的只读列表。
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * @return 错误说明，成功时为空。
     */
    public String getError() {
        return error;
    }

    /**
     * @return 稳定错误分类。
     */
    public GraphErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * @return 执行耗时，单位为毫秒。
     */
    public long getExecutionTimeMillis() {
        return executionTimeMillis;
    }

    /**
     * 将输入列表复制为不可变快照。
     */
    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
            ? Collections.<String>emptyList() : values));
    }
}
