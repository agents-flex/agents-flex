package com.agentsflex.doc.extractor;

import com.agentsflex.core.document.DataUriDocumentImagePublisher;
import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.renderer.MarkdownDocumentRenderer;
import com.agentsflex.doc.source.DocumentSource;

import java.io.IOException;

/**
 * 能够保留表格跨度等结构信息的文档解析器。
 *
 * <p>实现类不直接决定最终输出格式，而是先构建 {@link ExtractedDocument}。
 * 服务层随后根据调用方选择，将中间模型渲染为 Markdown、HTML 或融合格式。</p>
 */
public interface StructuredDocumentExtractor extends DocumentExtractor {

    /**
     * 将源文档解析为结构化中间模型。
     *
     * @param source                 文档输入源，解析期间可读取文件名、MIME 类型和内容流
     * @param documentImagePublisher 图片发布器，用于处理文档中的嵌入图片
     * @return 按源文档顺序排列的结构化文档，不应返回 {@code null}
     * @throws IOException 文档读取、格式解析或图片发布失败
     */
    ExtractedDocument extractDocument(DocumentSource source,
                                      DocumentImagePublisher documentImagePublisher) throws IOException;

    /**
     * 使用默认 Data URI 图片发布器解析并输出纯 Markdown。
     *
     * @param source 文档输入源
     * @return 纯 Markdown 文本
     * @throws IOException 文档读取或解析失败
     */
    @Override
    default String extractText(DocumentSource source) throws IOException {
        return extractText(source, new DataUriDocumentImagePublisher());
    }

    /**
     * 使用指定图片发布器解析并输出纯 Markdown。
     *
     * <p>该默认实现用于兼容既有 {@link DocumentExtractor} 接口；需要 HTML 或融合输出时，
     * 应由 {@code DocumentExtractionService} 取得结构化模型并选择对应渲染器。</p>
     *
     * @param source                 文档输入源
     * @param documentImagePublisher 图片发布器
     * @return 纯 Markdown 文本
     * @throws IOException 文档读取、解析或图片发布失败
     */
    @Override
    default String extractText(DocumentSource source,
                               DocumentImagePublisher documentImagePublisher) throws IOException {
        return new MarkdownDocumentRenderer().render(extractDocument(source, documentImagePublisher));
    }
}
