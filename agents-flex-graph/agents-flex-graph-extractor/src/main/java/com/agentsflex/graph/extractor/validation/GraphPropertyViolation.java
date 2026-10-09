package com.agentsflex.graph.extractor.validation;

/**
 * 单个 Schema 属性校验失败的结构化说明。
 *
 * <p>调用方可使用问题代码和属性名定位 UI 字段或映射国际化文案，不必解析错误文本。</p>
 */
public final class GraphPropertyViolation {
    /**
     * 机器可读的问题代码。
     */
    private final String code;
    /**
     * 出现问题的原始属性名；未知属性的键本身可能为空白或 null。
     */
    private final String propertyName;
    /**
     * 便于日志和直接抛错的可读说明。
     */
    private final String message;

    /**
     * 创建不可变问题说明。
     *
     * @param code         非空白的问题代码
     * @param propertyName 原始字段名；保留非法输入的空白或 null，避免坏候选中断整批校验
     * @param message      非空白的问题说明
     */
    public GraphPropertyViolation(String code, String propertyName, String message) {
        if (blank(code) || blank(message)) {
            throw new IllegalArgumentException("code and message must not be blank");
        }
        this.code = code;
        this.propertyName = propertyName;
        this.message = message;
    }

    /**
     * @return 机器可读的问题代码。
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 关联属性的原始键；非法输入的键可能为空白或 null。
     */
    public String getPropertyName() {
        return propertyName;
    }

    /**
     * @return 可读错误说明。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 判断文本是否为空白。
     */
    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
