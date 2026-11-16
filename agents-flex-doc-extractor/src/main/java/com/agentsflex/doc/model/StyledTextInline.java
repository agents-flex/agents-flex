package com.agentsflex.doc.model;

/**
 * 带有加粗、斜体、删除线或行内代码语义的文本。
 *
 * <p>多个样式可以同时启用，渲染器会按照目标格式生成可嵌套的标记。</p>
 */
public final class StyledTextInline implements InlineContent {
    /**
     * 文本内容。
     */
    private final String text;
    /**
     * 是否加粗。
     */
    private final boolean bold;
    /**
     * 是否斜体。
     */
    private final boolean italic;
    /**
     * 是否使用删除线。
     */
    private final boolean strikeThrough;
    /**
     * 是否使用行内代码语义。
     */
    private final boolean code;

    /**
     * 创建带样式的行内文本。
     *
     * @param text          文本内容
     * @param bold          是否加粗
     * @param italic        是否斜体
     * @param strikeThrough 是否使用删除线
     * @param code          是否使用行内代码语义
     */
    public StyledTextInline(String text, boolean bold, boolean italic,
                            boolean strikeThrough, boolean code) {
        this.text = text == null ? "" : text;
        this.bold = bold;
        this.italic = italic;
        this.strikeThrough = strikeThrough;
        this.code = code;
    }

    /**
     * @return 非 {@code null} 的文本内容
     */
    public String getText() {
        return text;
    }

    /**
     * @return 启用加粗时返回 {@code true}
     */
    public boolean isBold() {
        return bold;
    }

    /**
     * @return 启用斜体时返回 {@code true}
     */
    public boolean isItalic() {
        return italic;
    }

    /**
     * @return 启用删除线时返回 {@code true}
     */
    public boolean isStrikeThrough() {
        return strikeThrough;
    }

    /**
     * @return 使用行内代码语义时返回 {@code true}
     */
    public boolean isCode() {
        return code;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPlainText() {
        return text;
    }
}
