package com.agentsflex.doc.renderer;

import com.agentsflex.doc.DocumentOutputFormat;

/**
 * 内置文档渲染器工厂。
 *
 * <p>工厂当前返回无状态或仅持有无状态依赖的轻量渲染器实例，因此每次调用创建新实例，
 * 避免在并发提取请求之间共享可变状态。</p>
 */
public final class DocumentRenderers {

    /**
     * 工具类不允许实例化。
     */
    private DocumentRenderers() {
    }

    /**
     * 根据输出格式创建对应渲染器。
     *
     * @param format 输出格式；传入 {@code null} 时按纯 Markdown 处理
     * @return 与输出格式对应的渲染器实例
     */
    public static DocumentRenderer forFormat(DocumentOutputFormat format) {
        if (format == null || format == DocumentOutputFormat.MARKDOWN) return new MarkdownDocumentRenderer();
        if (format == DocumentOutputFormat.HTML) return new HtmlDocumentRenderer();
        return new HybridMarkdownRenderer();
    }
}
