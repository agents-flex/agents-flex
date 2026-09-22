package com.agentsflex.graph.schema;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将请求的可移植 Schema 与后端现状比较后的结果。
 */
public final class GraphSchemaValidation {
    /**
     * 阻止应用 Schema 的错误。
     */
    private final List<String> errors;
    /**
     * 不阻止应用、但需要调用方注意的兼容性提醒。
     */
    private final List<String> warnings;

    /**
     * 创建并复制校验结果列表。
     */
    public GraphSchemaValidation(List<String> errors, List<String> warnings) {
        this.errors = immutable(errors);
        this.warnings = immutable(warnings);
    }

    /**
     * @return 没有错误和警告的成功结果
     */
    public static GraphSchemaValidation valid() {
        return new GraphSchemaValidation(Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    /**
     * @return 是否不存在错误
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * @return 只读错误列表
     */
    public List<String> getErrors() {
        return errors;
    }

    /**
     * @return 只读警告列表
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * 将空值或输入集合转换为不可变快照。
     */
    private static List<String> immutable(List<String> source) {
        return source == null ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(source));
    }
}
