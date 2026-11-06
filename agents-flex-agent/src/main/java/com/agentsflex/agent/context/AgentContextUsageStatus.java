package com.agentsflex.agent.context;

/**
 * 当前模型可见上下文的服务端 Usage 基准状态。
 *
 * <p>状态只描述供应商确认的数据是否可作为当前上下文基准，不接受本地估算值。</p>
 */
public enum AgentContextUsageStatus {
    /**
     * 尚未收到过完整的供应商 Usage，无法显示可靠比例和剩余量。
     */
    NO_CONFIRMED_USAGE,
    /**
     * 最近一次完整供应商 Usage 与当前模型可见上下文一致，可以作为展示基准。
     */
    CONFIRMED_BASELINE,
    /**
     * 上下文已压缩或后续响应缺少完整 Usage，旧基准仅保留审计用途，不再有效。
     */
    BASELINE_INVALIDATED
}
