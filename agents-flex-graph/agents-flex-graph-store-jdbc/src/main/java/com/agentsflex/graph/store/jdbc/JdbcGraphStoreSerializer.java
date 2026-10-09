package com.agentsflex.graph.store.jdbc;

/**
 * 图谱 Store 不可变快照的二进制序列化扩展点。
 *
 * <p>数据库表会把用于过滤、排序和乐观锁的字段单独投影到关系列，其余完整领域对象由本接口
 * 编码到二进制 payload。应用可以替换默认实现，以支持格式迁移、压缩或加密。</p>
 *
 * <p>实现必须能够可靠还原当前版本的领域对象，并应将序列化结果视为持久化协议；修改格式时
 * 需要同步考虑已有数据的兼容和迁移。</p>
 */
public interface JdbcGraphStoreSerializer {
    /**
     * 将领域快照编码为数据库可保存的字节数组。
     *
     * @param value 待编码的非空领域对象
     * @return 编码后的字节数组
     */
    byte[] serialize(Object value);

    /**
     * 将数据库 payload 还原为指定类型的领域快照。
     *
     * @param value 数据库中读取的字节数组，可以为空
     * @param type  目标对象类型
     * @param <T>   目标对象类型参数
     * @return 还原后的对象；如何处理空字节数组由实现约定
     */
    <T> T deserialize(byte[] value, Class<T> type);
}
