package com.agentsflex.core.model.chat;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;

/**
 * 聊天模型稳定的 Token 容量能力描述。
 *
 * <p>该对象描述模型自身的能力上限，不代表某一次请求实际消耗的 Token，也不参与本地 Token
 * 估算。上下文管理模块只使用这里的容量上限与供应商响应返回的 Usage 数据计算占用比例。</p>
 *
 * <p>{@code contextWindowTokens} 或 {@code maxOutputTokens} 为 0 时表示调用方没有提供该能力，
 * 此时框架不会根据未知容量推导剩余 Token 或触发比例阈值。</p>
 */
public final class ChatModelCapabilities implements Serializable {
    /**
     * Java 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 无任何已知容量信息时复用的不可变单例。
     */
    private static final ChatModelCapabilities UNKNOWN =
        new ChatModelCapabilities(null, 0, 0);

    /**
     * 模型稳定标识，通常为 {@code provider:model}；用于隔离不同模型的 Usage 基准。
     */
    private final String modelKey;
    /**
     * 模型可接受的输入与输出总上下文容量；0 表示未知。
     */
    private final long contextWindowTokens;
    /**
     * 模型允许的最大输出 Token；0 表示未知。
     */
    private final long maxOutputTokens;

    /**
     * 创建模型能力描述。
     *
     * @param modelKey            稳定模型标识；空白值会被规范化为 {@code null}
     * @param contextWindowTokens 总上下文容量；0 表示未知
     * @param maxOutputTokens     最大输出容量；0 表示未知
     */
    public ChatModelCapabilities(String modelKey, long contextWindowTokens,
                                 long maxOutputTokens) {
        if (contextWindowTokens < 0 || maxOutputTokens < 0) {
            throw new IllegalArgumentException("model token capabilities must not be negative");
        }
        this.modelKey = StringUtil.hasText(modelKey) ? modelKey.trim() : null;
        this.contextWindowTokens = contextWindowTokens;
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * @return 不包含任何容量信息的共享实例
     */
    public static ChatModelCapabilities unknown() {
        return UNKNOWN;
    }

    /**
     * @return 稳定模型标识；未知时为 {@code null}
     */
    public String getModelKey() {
        return modelKey;
    }

    /**
     * @return 总上下文容量；0 表示未知
     */
    public long getContextWindowTokens() {
        return contextWindowTokens;
    }

    /**
     * @return 最大输出 Token；0 表示未知
     */
    public long getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * @return 是否已经配置有效的总上下文容量
     */
    public boolean hasContextWindowTokens() {
        return contextWindowTokens > 0;
    }
}
