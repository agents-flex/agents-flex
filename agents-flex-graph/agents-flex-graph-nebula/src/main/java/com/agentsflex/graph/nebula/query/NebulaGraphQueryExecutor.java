package com.agentsflex.graph.nebula.query;

import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.nebula.NebulaGraphStore;
import com.agentsflex.graph.nebula.NebulaGraphStoreConfig;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 将统一查询编译为 nGQL，并转换 Nebula ResultSet。
 */
public final class NebulaGraphQueryExecutor implements GraphQueryExecutor {
    /**
     * 所属存储。
     */
    private final NebulaGraphStore store;
    /**
     * 默认空间配置。
     */
    private final NebulaGraphStoreConfig config;

    /**
     * 创建查询执行器。
     */
    public NebulaGraphQueryExecutor(NebulaGraphStore store, NebulaGraphStoreConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * 编译并执行可移植遍历查询。
     */
    @Override
    public GraphResult execute(GraphQuery query, com.agentsflex.graph.GraphOptions options) {
        if (!(query instanceof TraversalQuery))
            throw new IllegalArgumentException("Unsupported portable Nebula query: " + query.getClass().getName());
        return run(new NebulaNqlCompiler().compile((TraversalQuery) query), options);
    }

    /**
     * 执行原生 nGQL。
     */
    @Override
    public GraphResult execute(NativeGraphQuery query, com.agentsflex.graph.GraphOptions options) {
        return run(new NebulaNqlCompiler().compile(query), options);
    }

    /**
     * 在目标空间会话池执行并物化结果。
     */
    private GraphResult run(NebulaNqlCompiler.Compiled compiled, com.agentsflex.graph.GraphOptions options) {
        long started = System.currentTimeMillis();
        try {
            com.agentsflex.graph.GraphOptions resolved = options == null
                ? com.agentsflex.graph.GraphOptions.DEFAULT : options;
            ResultSet result = store.pool(resolved.getSpaceOrDefault(config.getDefaultSpace()))
                .execute(compiled.statement, compiled.parameters);
            if (!result.isSucceeded())
                throw new IllegalStateException("Nebula query failed: " + result.getErrorMessage());
            List<GraphRecord> records = new ArrayList<>();
            List<String> columns = result.getColumnNames();
            int count = Math.min(result.rowsSize(), resolved.getMaxRecords());
            for (int i = 0; i < count; i++) {
                ResultSet.Record row = result.rowValues(i);
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                for (String column : columns) values.put(column, convert(row.get(column)));
                records.add(new GraphRecord(values));
            }
            return new GraphResult(records, compiled.statement,
                new GraphResultMetadata(records.size(), result.rowsSize() > count,
                    Math.max(0L, System.currentTimeMillis() - started)));
        } catch (Exception e) {
            throw new IllegalStateException("Nebula query failed: " + e.getMessage(), e);
        }
    }

    /**
     * 将 Nebula ValueWrapper 转换为 Java 基础值或列表。
     */
    private Object convert(ValueWrapper value) throws UnsupportedEncodingException {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isLong()) return value.asLong();
        if (value.isDouble()) return value.asDouble();
        if (value.isString()) return value.asString();
        if (value.isList()) {
            List<Object> result = new ArrayList<>();
            for (ValueWrapper item : value.asList()) result.add(convert(item));
            return result;
        }
        return value.toString();
    }
}
