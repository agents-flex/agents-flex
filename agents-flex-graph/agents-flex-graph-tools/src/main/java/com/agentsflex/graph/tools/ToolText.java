package com.agentsflex.graph.tools;

/**
 * Tool 描述和软错误文本的最小化清理工具。
 *
 * <p>自由文本可能来自应用配置或后端异常。本类负责限制长度、移除不可见控制字符，并在文本嵌入
 * XML 风格提示片段时完成转义。</p>
 */
final class ToolText {
    /**
     * 工具类不允许实例化。
     */
    private ToolText() {
    }

    /**
     * 清理并截断自由文本。
     *
     * <p>保留换行、回车和制表符以维持说明文本结构，其他 ISO 控制字符会被丢弃。</p>
     *
     * @param value     原始文本，可以为 null
     * @param maxLength 最大字符数
     * @return 清理后的文本
     */
    static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && result.length() < maxLength; i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t' || !Character.isISOControl(ch)) {
                result.append(ch);
            }
        }
        return result.toString().trim();
    }

    /**
     * 清理文本并转义 XML 特殊字符，避免知识源描述破坏 Tool 中的结构化提示片段。
     */
    static String xml(String value, int maxLength) {
        return clean(value, maxLength)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
