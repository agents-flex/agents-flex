package com.agentsflex.doc.model;

/**
 * 段落、标题等块级元素内部的行内内容。
 *
 * <p>行内模型用于保留文本、链接、图片、换行和字符样式等语义，避免解析阶段提前
 * 拼接 Markdown 或 HTML。具体输出格式由渲染器决定。</p>
 */
public interface InlineContent {

    /**
     * 获取便于检索、兼容旧接口和降级输出使用的纯文本表示。
     *
     * @return 非 {@code null} 的纯文本
     */
    String getPlainText();
}
