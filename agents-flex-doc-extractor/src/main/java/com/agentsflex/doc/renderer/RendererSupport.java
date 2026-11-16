package com.agentsflex.doc.renderer;

/**
 * 渲染器共享的转义与块间距辅助方法。
 */
final class RendererSupport {

    /**
     * 工具类不允许实例化。
     */
    private RendererSupport() {
    }

    /**
     * 对文本中的 HTML 特殊字符进行实体转义。
     *
     * @param value 原始文本
     * @return 可安全放入 HTML 文本节点的字符串
     */
    static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * 将单元格或普通文本转换为安全 HTML，并将换行转换为 {@code br}。
     *
     * @param value 原始文本
     * @return HTML 安全文本
     */
    static String renderCellHtml(String value) {
        return escapeHtml(value).replace("\r\n", "<br>")
            .replace("\r", "<br>")
            .replace("\n", "<br>");
    }

    /**
     * 保证当前输出末尾至少存在一个空行。
     *
     * <p>该方法用于分隔 Markdown 块和 HTML 块，防止相邻内容被 Markdown 解析器错误合并。</p>
     *
     * @param output 目标输出缓冲区
     */
    static void ensureBlankLine(StringBuilder output) {
        if (output.length() == 0) return;
        if (output.charAt(output.length() - 1) != '\n') output.append('\n');
        if (output.length() < 2 || output.charAt(output.length() - 2) != '\n') output.append('\n');
    }
}
