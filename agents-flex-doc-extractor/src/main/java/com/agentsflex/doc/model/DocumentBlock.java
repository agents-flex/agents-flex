package com.agentsflex.doc.model;

/**
 * 文档中的一个结构化块。
 *
 * <p>块是渲染阶段的最小顶层单位，例如段落、标题、列表项和表格。
 * 各解析器应按照源文档中的视觉顺序将块加入 {@link ExtractedDocument}。</p>
 */
public interface DocumentBlock {
}
