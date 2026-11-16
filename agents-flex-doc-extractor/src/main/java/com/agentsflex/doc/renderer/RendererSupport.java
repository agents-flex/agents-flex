package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.ImageInline;
import com.agentsflex.doc.model.InlineContent;
import com.agentsflex.doc.model.LineBreakInline;
import com.agentsflex.doc.model.LinkInline;
import com.agentsflex.doc.model.StyledTextInline;
import com.agentsflex.doc.model.TextInline;

import java.net.URI;
import java.util.List;
import java.util.Locale;

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

    /**
     * 将行内内容渲染为 Markdown。
     *
     * @param content 行内内容
     * @return Markdown 片段
     */
    static String renderMarkdownInlines(List<InlineContent> content) {
        StringBuilder output = new StringBuilder();
        if (content == null) return "";
        for (InlineContent inline : content) {
            if (inline instanceof TextInline) {
                output.append(((TextInline) inline).getText());
            } else if (inline instanceof StyledTextInline) {
                StyledTextInline styled = (StyledTextInline) inline;
                String value = styled.getText();
                if (styled.isCode()) value = "\u0060" + value.replace("\u0060", "\\\u0060") + "\u0060";
                if (styled.isBold()) value = "**" + value + "**";
                if (styled.isItalic()) value = "*" + value + "*";
                if (styled.isStrikeThrough()) value = "~~" + value + "~~";
                output.append(value);
            } else if (inline instanceof LineBreakInline) {
                output.append("  \n");
            } else if (inline instanceof ImageInline) {
                ImageInline image = (ImageInline) inline;
                if (isSafeUrl(image.getSource(), true)) {
                    output.append("![").append(escapeMarkdownLabel(image.getAltText())).append("](")
                        .append(image.getSource());
                    if (image.getTitle() != null && !image.getTitle().isEmpty()) {
                        output.append(" \"").append(image.getTitle().replace("\"", "\\\"")).append('"');
                    }
                    output.append(')');
                }
            } else if (inline instanceof LinkInline) {
                LinkInline link = (LinkInline) inline;
                String label = renderMarkdownInlines(link.getContent());
                if (isSafeUrl(link.getUrl(), false)) {
                    output.append('[').append(label).append("](").append(link.getUrl());
                    if (link.getTitle() != null && !link.getTitle().isEmpty()) {
                        output.append(" \"").append(link.getTitle().replace("\"", "\\\"")).append('"');
                    }
                    output.append(')');
                } else output.append(label);
            }
        }
        return output.toString();
    }

    /**
     * 将行内内容渲染为安全 HTML。
     *
     * @param content 行内内容
     * @return HTML 片段
     */
    static String renderHtmlInlines(List<InlineContent> content) {
        StringBuilder output = new StringBuilder();
        if (content == null) return "";
        for (InlineContent inline : content) {
            if (inline instanceof TextInline) {
                output.append(renderCellHtml(((TextInline) inline).getText()));
            } else if (inline instanceof StyledTextInline) {
                StyledTextInline styled = (StyledTextInline) inline;
                String value = renderCellHtml(styled.getText());
                if (styled.isCode()) value = "<code>" + value + "</code>";
                if (styled.isBold()) value = "<strong>" + value + "</strong>";
                if (styled.isItalic()) value = "<em>" + value + "</em>";
                if (styled.isStrikeThrough()) value = "<del>" + value + "</del>";
                output.append(value);
            } else if (inline instanceof LineBreakInline) output.append("<br>");
            else if (inline instanceof ImageInline) {
                ImageInline image = (ImageInline) inline;
                appendHtmlImage(output, image.getSource(), image.getAltText(), image.getTitle());
            } else if (inline instanceof LinkInline) {
                LinkInline link = (LinkInline) inline;
                String label = renderHtmlInlines(link.getContent());
                if (isSafeUrl(link.getUrl(), false)) {
                    output.append("<a href=\"").append(escapeHtml(link.getUrl())).append('"');
                    if (link.getTitle() != null && !link.getTitle().isEmpty()) {
                        output.append(" title=\"").append(escapeHtml(link.getTitle())).append('"');
                    }
                    output.append('>').append(label).append("</a>");
                } else output.append(label);
            }
        }
        return output.toString();
    }

    /**
     * 将图片安全地追加为 HTML img 标签。
     */
    static void appendHtmlImage(StringBuilder output, String source, String altText, String title) {
        if (!isSafeUrl(source, true)) return;
        output.append("<img src=\"").append(escapeHtml(source)).append("\" alt=\"")
            .append(escapeHtml(altText)).append('"');
        if (title != null && !title.isEmpty()) output.append(" title=\"").append(escapeHtml(title)).append('"');
        output.append('>');
    }

    /**
     * 判断 URL 是否允许输出，拒绝脚本协议和非图片 data 地址。
     */
    static boolean isSafeUrl(String value, boolean image) {
        if (value == null || value.trim().isEmpty()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("javascript:") || normalized.startsWith("vbscript:")) return false;
        if (normalized.startsWith("data:")) return image && normalized.startsWith("data:image/");
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return scheme == null || "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme) || "mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 对 Markdown 标签中的方括号进行转义。
     */
    private static String escapeMarkdownLabel(String value) {
        return value == null ? "" : value.replace("[", "\\[").replace("]", "\\]");
    }
}
