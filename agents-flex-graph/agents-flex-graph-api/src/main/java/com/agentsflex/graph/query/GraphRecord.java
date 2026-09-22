package com.agentsflex.graph.query;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图查询返回的一行投影记录。
 */
public final class GraphRecord {
    /**
     * 投影列名到值的只读映射。
     */
    private final Map<String, Object> values;

    /**
     * 创建记录并复制输入映射。
     */
    public GraphRecord(Map<String, ?> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (values != null) copy.putAll(values);
        this.values = Collections.unmodifiableMap(copy);
    }

    /**
     * @return 所有投影值
     */
    public Map<String, Object> getValues() {
        return values;
    }

    /**
     * @param name 列名 @return 对应值，不存在时返回 {@code null}
     */
    public Object get(String name) {
        return values.get(name);
    }
}
