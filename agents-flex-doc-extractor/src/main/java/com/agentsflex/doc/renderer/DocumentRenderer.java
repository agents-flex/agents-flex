package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.ExtractedDocument;

/**
 * 结构化文档渲染器。
 *
 * <p>渲染器只负责将 {@link ExtractedDocument} 序列化为目标文本格式，不重新解析源文件，
 * 也不修改传入的结构化模型。</p>
 */
public interface DocumentRenderer {

    /**
     * 渲染结构化文档。
     *
     * @param document 待渲染的结构化文档，可为 {@code null}
     * @return 渲染后的文本；文档为空时返回空字符串
     */
    String render(ExtractedDocument document);
}
