package com.agentsflex.doc.model;

/**
 * 不带额外样式的普通行内文本。
 */
public final class TextInline implements InlineContent {
    /**
     * 普通文本内容。
     */
    private final String text;

    /**
     * 创建普通行内文本。
     *
     * @param text 文本内容，{@code null} 会转换为空字符串
     */
    public TextInline(String text) {
        this.text = text == null ? "" : text;
    }

    /**
     * 获取原始文本。
     *
     * @return 非 {@code null} 的文本
     */
    public String getText() {
        return text;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPlainText() {
        return text;
    }
}
