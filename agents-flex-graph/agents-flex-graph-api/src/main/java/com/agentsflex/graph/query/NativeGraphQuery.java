package com.agentsflex.graph.query;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端原生查询的显式逃生口。
 *
 * <p>语句由调用方提供，但值始终通过参数映射传递，适配器不得把参数直接拼接进文本。</p>
 */
public final class NativeGraphQuery {
    /**
     * 原生查询语句。
     */
    private final String statement;
    /**
     * 参数名到参数值的只读映射。
     */
    private final Map<String, Object> parameters;

    /**
     * 创建并冻结原生查询。
     */
    private NativeGraphQuery(String statement, Map<String, ?> parameters) {
        this.statement = GraphIdentifiers.requireText(statement, "native statement");
        Map<String, Object> copy = new LinkedHashMap<>();
        if (parameters != null) copy.putAll(parameters);
        this.parameters = Collections.unmodifiableMap(copy);
    }

    /**
     * @param statement 原生语句 @param parameters 参数 @return 原生查询对象
     */
    public static NativeGraphQuery of(String statement, Map<String, ?> parameters) {
        return new NativeGraphQuery(statement, parameters);
    }

    /**
     * @return 原生语句
     */
    public String getStatement() {
        return statement;
    }

    /**
     * @return 只读参数映射
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }
}
