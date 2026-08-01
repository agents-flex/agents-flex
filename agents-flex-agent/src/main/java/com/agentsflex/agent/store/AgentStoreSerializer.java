package com.agentsflex.agent.store;

import java.io.Serializable;

/** Agent Store 对持久化值对象进行编码和解码的扩展接口。 */
public interface AgentStoreSerializer {
    /** 将可持久化值对象编码为数据库或 KV 可以保存的二进制内容。 */
    byte[] serialize(Serializable value);
    /** 将受信任的持久化内容恢复为指定类型。 */
    <T> T deserialize(byte[] bytes, Class<T> type);
}
