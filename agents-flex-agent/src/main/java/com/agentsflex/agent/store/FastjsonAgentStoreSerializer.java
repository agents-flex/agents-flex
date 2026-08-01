package com.agentsflex.agent.store;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;

import java.io.Serializable;
import java.util.Arrays;

/**
 * 使用 fastjson2 JSONB 编码 Agent 持久化值对象的默认实现。
 *
 * <p>JSONB 会保留消息等多态字段的具体 Java 类型，使 {@code AgentRunSnapshot}、恢复命令和
 * 持久化事件能够完整还原。默认只允许解析 Agents-Flex 类型和常用 JDK 值类型，不会对持久化内容
 * 开启不受限制的 AutoType。应用若在 metadata 或 modeState 中保存自定义值对象，应通过
 * {@link #FastjsonAgentStoreSerializer(String...)} 显式加入对应的包名前缀。</p>
 *
 * <p>序列化结果是 fastjson2 JSONB 二进制数据，不是 UTF-8 JSON 文本。需要跨语言或人工查看的
 * 存储格式时，可以实现 {@link AgentStoreSerializer} 替换该默认实现。</p>
 */
public final class FastjsonAgentStoreSerializer implements AgentStoreSerializer {

    /** 默认允许从持久化数据恢复的类型包前缀。 */
    private static final String[] DEFAULT_ACCEPTED_TYPE_PREFIXES = {
        "com.agentsflex.agent.",
        "com.agentsflex.core.",
        "java.lang.",
        "java.math.",
        "java.time.",
        "java.util."
    };

    /** 在 fastjson2 处理类型信息前执行的白名单校验器。 */
    private final JSONReader.AutoTypeBeforeHandler autoTypeFilter;

    /** 创建仅允许恢复框架类型和常用 JDK 类型的默认序列化器。 */
    public FastjsonAgentStoreSerializer() {
        this(new String[0]);
    }

    /**
     * 创建允许恢复额外业务类型的序列化器。
     *
     * <p>传入值应为尽可能精确的完整类名或包名前缀，例如
     * {@code com.example.agent.state.}。默认白名单始终保留。</p>
     *
     * @param additionalAcceptedTypePrefixes 允许从 metadata 或 modeState 恢复的额外类型前缀
     */
    public FastjsonAgentStoreSerializer(String... additionalAcceptedTypePrefixes) {
        String[] additional = additionalAcceptedTypePrefixes == null
            ? new String[0] : additionalAcceptedTypePrefixes.clone();
        String[] accepted = Arrays.copyOf(DEFAULT_ACCEPTED_TYPE_PREFIXES,
            DEFAULT_ACCEPTED_TYPE_PREFIXES.length + additional.length);
        for (int index = 0; index < additional.length; index++) {
            String prefix = additional[index];
            if (prefix == null || prefix.trim().isEmpty()) {
                throw new IllegalArgumentException("accepted type prefix must not be blank");
            }
            accepted[DEFAULT_ACCEPTED_TYPE_PREFIXES.length + index] = prefix;
        }
        this.autoTypeFilter = JSONReader.autoTypeFilter(true, accepted);
    }

    /**
     * 将可序列化状态编码为独立 JSONB 字节数组。
     *
     * <p>{@code WriteClassName} 用于保存接口字段和 {@code Object} 元数据值的实际类型；
     * {@code FieldBased} 允许不可变值对象在没有公开 setter 的情况下完整保存字段。</p>
     */
    @Override
    public byte[] serialize(Serializable value) {
        try {
            return JSONB.toBytes(value,
                JSONWriter.Feature.WriteClassName,
                JSONWriter.Feature.FieldBased,
                JSONWriter.Feature.ReferenceDetection,
                JSONWriter.Feature.ErrorOnNoneSerializable,
                JSONWriter.Feature.NotWriteHashMapArrayListClassName);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Agent state contains a non-serializable value", error);
        }
    }

    /**
     * 解码 JSONB 字节数组并校验结果可转换为目标类型；空数组引用返回 {@code null}。
     *
     * <p>反序列化采用字段模式恢复不可变值对象，并使用白名单处理 JSONB 中携带的多态类型信息。</p>
     */
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        if (bytes == null) return null;
        if (type == null) {
            throw new IllegalArgumentException("target type must not be null");
        }
        try {
            return JSONB.parseObject(bytes, type, autoTypeFilter,
                JSONReader.Feature.FieldBased,
                JSONReader.Feature.UseNativeObject,
                JSONReader.Feature.ErrorOnNotSupportAutoType);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Stored Agent state cannot be decoded as " + type.getName(), error);
        }
    }
}
