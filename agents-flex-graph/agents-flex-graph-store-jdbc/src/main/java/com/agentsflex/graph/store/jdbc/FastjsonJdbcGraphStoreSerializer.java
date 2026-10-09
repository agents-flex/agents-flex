package com.agentsflex.graph.store.jdbc;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;

/**
 * 基于 Fastjson2 JSONB 的默认快照序列化器。
 *
 * <p>反序列化启用 {@link JSONReader.Feature#FieldBased}，从而可以恢复没有无参构造器、仅包含
 * final 字段的不可变图谱领域对象。JSONB 是模块内部的持久化格式，不应由 SQL 查询直接解析。</p>
 */
public final class FastjsonJdbcGraphStoreSerializer implements JdbcGraphStoreSerializer {
    /**
     * 使用 Fastjson2 JSONB 编码完整对象图。
     */
    @Override
    public byte[] serialize(Object value) {
        return JSONB.toBytes(value);
    }

    /**
     * 使用字段模式恢复不可变对象；数据库空值直接映射为 {@code null}。
     */
    @Override
    public <T> T deserialize(byte[] value, Class<T> type) {
        return value == null ? null : JSONB.parseObject(value, type, JSONReader.Feature.FieldBased);
    }
}
