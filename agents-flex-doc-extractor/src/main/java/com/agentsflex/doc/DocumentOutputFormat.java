package com.agentsflex.doc;

/**
 * 文档提取结果的输出格式。
 *
 * <p>该枚举只影响结构化解析器的最终序列化方式，不改变源文档的解析逻辑。
 * 原有未指定格式的入口始终使用 {@link #MARKDOWN}，以保持向后兼容。</p>
 */
public enum DocumentOutputFormat {
    /**
     * 纯 Markdown 输出。
     *
     * <p>由于标准 Markdown 不支持合并单元格，表格跨度会降级为规则二维表格，
     * 合并区域仅在左上角锚点位置保留文本。</p>
     */
    MARKDOWN,

    /**
     * 完整 HTML 片段输出。
     *
     * <p>标题、段落和表格均转换为 HTML；表格通过 {@code rowspan} 和
     * {@code colspan} 保留合并单元格。</p>
     */
    HTML,

    /**
     * Markdown 与 HTML 表格融合输出。
     *
     * <p>普通正文继续使用 Markdown，所有表格统一输出为 HTML，适合既希望保留
     * Markdown 可读性，又需要无损表达合并单元格的场景。</p>
     */
    MARKDOWN_WITH_HTML_TABLES
}
