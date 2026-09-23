package com.agentsflex.graph.error;

/**
 * Graph SDK 可识别的稳定错误分类。
 *
 * <p>错误消息可能来自具体数据库并会变化；上层工具应优先依赖该枚举进行分类，
 * 再把 {@code getMessage()} 作为诊断详情展示。</p>
 */
public enum GraphErrorCode {
    /**
     * 未进一步分类的错误。
     */
    UNKNOWN,
    /**
     * 标识符、参数或查询结构不合法。
     */
    INVALID_ARGUMENT,
    /**
     * 后端不支持请求能力。
     */
    UNSUPPORTED_FEATURE,
    /**
     * 无法建立或验证连接。
     */
    CONNECTION_FAILED,
    /**
     * 图空间不存在或不可访问。
     */
    SPACE_NOT_FOUND,
    /**
     * Schema 校验失败。
     */
    SCHEMA_VALIDATION_FAILED,
    /**
     * Schema 应用失败。
     */
    SCHEMA_APPLY_FAILED,
    /**
     * 查询执行失败。
     */
    QUERY_FAILED,
    /**
     * 查询超过超时限制。
     */
    QUERY_TIMEOUT,
    /**
     * 查询结果超过保护上限。
     */
    QUERY_LIMIT_EXCEEDED,
    /**
     * 节点、边写入失败。
     */
    WRITE_FAILED,
    /**
     * 导入批次失败。
     */
    IMPORT_BATCH_FAILED,
    /**
     * 导入任务被取消。
     */
    IMPORT_CANCELLED,
    /**
     * 事务操作失败。
     */
    TRANSACTION_FAILED
}
