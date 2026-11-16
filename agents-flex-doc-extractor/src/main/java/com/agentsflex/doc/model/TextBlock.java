package com.agentsflex.doc.model;

/**
 * 标题、段落、列表项或已格式化 Markdown 文本块。
 *
 * <p>该模型保存文本的语义类型，而不是预先绑定某一种输出格式；不同渲染器可将
 * 同一文本块分别转换为 Markdown 标记或 HTML 元素。</p>
 */
public final class TextBlock implements DocumentBlock {
    /**
     * 文本块的语义类型。
     */
    public enum Type {
        /**
         * 普通段落。
         */
        PARAGRAPH,
        /**
         * 标题，具体级别由 {@link TextBlock#getLevel()} 提供。
         */
        HEADING,
        /**
         * 无序列表项。
         */
        LIST_ITEM,
        /**
         * 已经包含 Markdown 标记、在纯 Markdown 模式下直接输出的文本。
         */
        RAW_MARKDOWN
    }

    /**
     * 文本块的语义类型。
     */
    private final Type type;

    /**
     * 文本内容；构造时将 {@code null} 规范为空字符串。
     */
    private final String text;

    /**
     * 标题级别；非标题块使用 0。
     */
    private final int level;

    /**
     * 创建文本块；外部调用方应优先使用语义明确的静态工厂方法。
     *
     * @param type  文本块类型
     * @param text  文本内容
     * @param level 标题级别
     */
    private TextBlock(Type type, String text, int level) {
        this.type = type;
        this.text = text == null ? "" : text;
        this.level = level;
    }

    /**
     * 创建普通段落块。
     *
     * @param text 段落文本
     * @return 新的段落块
     */
    public static TextBlock paragraph(String text) {
        return new TextBlock(Type.PARAGRAPH, text, 0);
    }

    /**
     * 创建标题块。
     *
     * <p>标题级别自动限制在 1 至 6 之间，以匹配 Markdown 和 HTML 的标准标题范围。</p>
     *
     * @param text  标题文本
     * @param level 标题级别
     * @return 新的标题块
     */
    public static TextBlock heading(String text, int level) {
        return new TextBlock(Type.HEADING, text, Math.max(1, Math.min(6, level)));
    }

    /**
     * 创建无序列表项。
     *
     * @param text 列表项文本
     * @return 新的列表项块
     */
    public static TextBlock listItem(String text) {
        return new TextBlock(Type.LIST_ITEM, text, 0);
    }

    /**
     * 创建原始 Markdown 文本块。
     *
     * <p>纯 Markdown 和融合模式会直接输出该文本；完整 HTML 模式出于安全考虑将其作为
     * 普通文本转义，而不会将其中的 Markdown 当作 HTML 执行。</p>
     *
     * @param text Markdown 文本
     * @return 新的原始 Markdown 块
     */
    public static TextBlock rawMarkdown(String text) {
        return new TextBlock(Type.RAW_MARKDOWN, text, 0);
    }

    /**
     * 获取文本块类型。
     *
     * @return 文本块类型
     */
    public Type getType() {
        return type;
    }

    /**
     * 获取文本内容。
     *
     * @return 非 {@code null} 的文本内容
     */
    public String getText() {
        return text;
    }

    /**
     * 获取标题级别。
     *
     * @return 标题块返回 1 至 6，其他类型返回 0
     */
    public int getLevel() {
        return level;
    }
}
