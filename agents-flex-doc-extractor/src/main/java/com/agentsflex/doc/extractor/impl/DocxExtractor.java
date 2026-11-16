/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.doc.extractor.impl;

import com.agentsflex.doc.extractor.MarkdownFormatter;
import com.agentsflex.doc.extractor.StructuredDocumentExtractor;
import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.model.*;
import com.agentsflex.doc.source.DocumentSource;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * DOCX 文档提取器（.docx, .dotx）
 * 支持段落、表格、列表文本提取，以及嵌入图片处理
 */
public class DocxExtractor implements StructuredDocumentExtractor {

    /**
     * Word Open XML 格式已知的精确 MIME 类型集合。
     */
    private static final Set<String> KNOWN_MIME_TYPES;

    /**
     * 用于兼容带参数或变体 MIME 类型的 Open XML Word MIME 前缀。
     */
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.wordprocessingml";

    /**
     * 支持的 Word Open XML 文件扩展名集合。
     */
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        // 精确 MIME（可选）
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.template");
        mimeTypes.add("application/vnd.ms-word.document.macroenabled.12");
        mimeTypes.add("application/vnd.ms-word.template.macroenabled.12");
        KNOWN_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        // 支持的扩展名
        Set<String> extensions = new HashSet<>();
        extensions.add("docx");
        extensions.add("dotx");
        extensions.add("docm");
        extensions.add("dotm");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    /**
     * 判断输入源是否属于 DOCX、DOTX、DOCM 或 DOTM 格式。
     *
     * <p>优先匹配标准化后的 MIME 类型，无法判断时再按文件扩展名匹配。</p>
     *
     * @param source 文档输入源
     * @return 支持该输入源时返回 {@code true}
     */
    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();
        String normalizedMimeType = mimeType != null
            ? mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)
            : null;

        // 1. MIME 精确匹配
        if (normalizedMimeType != null && KNOWN_MIME_TYPES.contains(normalizedMimeType)) {
            return true;
        }

        // 2. MIME 前缀匹配
        if (normalizedMimeType != null && normalizedMimeType.startsWith(MIME_PREFIX)) {
            return true;
        }

        // 3. 扩展名匹配
        if (fileName != null) {
            String ext = getExtension(fileName);
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    /**
     * 将 DOCX 文档解析为结构化文档模型。
     *
     * <p>正文段落和表格按照 Word 文档中的原始顺序输出。表格横向合并读取
     * {@code gridSpan}，纵向合并读取 {@code vMerge} 的 restart/continue 标记。</p>
     *
     * @param source                 DOCX 文档输入源
     * @param documentImagePublisher 嵌入图片发布器
     * @return 结构化文档
     * @throws IOException 文档读取、解析或图片发布失败
     */
    @Override
    public ExtractedDocument extractDocument(DocumentSource source,
                                             DocumentImagePublisher documentImagePublisher) throws IOException {
        ExtractedDocument extracted = new ExtractedDocument();

        try (InputStream is = source.openStream();
             XWPFDocument document = new XWPFDocument(is)) {

            ListBlock currentList = null;
            BigInteger currentListId = null;
            // 按文档中的原始顺序提取段落和表格，并合并连续列表段落。
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) bodyElement;
                    DocumentBlock block = extractParagraphBlock(paragraph, document,
                        documentImagePublisher);
                    if (block == null) continue;
                    if (paragraph.getNumID() != null) {
                        boolean ordered = isOrderedList(paragraph, document);
                        if (currentList == null || !paragraph.getNumID().equals(currentListId)
                            || currentList.isOrdered() != ordered) {
                            currentList = new ListBlock(ordered);
                            currentListId = paragraph.getNumID();
                            extracted.addBlock(currentList);
                        }
                        currentList.addItem(new ListItem().addBlock(block));
                    } else {
                        currentList = null;
                        currentListId = null;
                        extracted.addBlock(block);
                    }
                } else if (bodyElement instanceof XWPFTable) {
                    currentList = null;
                    currentListId = null;
                    TableBlock table = extractStructuredTable((XWPFTable) bodyElement,
                        document, documentImagePublisher);
                    if (!table.isEmpty()) extracted.addBlock(table);
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to extract DOCX: " + e.getMessage(), e);
        }

        return extracted;
    }

    /**
     * 提取段落文本，包括其中嵌入的图片
     *
     * @param paragraph              Word 段落
     * @param documentImagePublisher 图片发布器
     * @return 段落内容；完全为空时返回 {@code null}
     * @throws IOException 图片发布失败
     */
    private DocumentBlock extractParagraphBlock(XWPFParagraph paragraph, XWPFDocument document,
                                                DocumentImagePublisher documentImagePublisher) throws IOException {
        List<InlineContent> content = new java.util.ArrayList<>();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText != null && !runText.isEmpty()) {
                InlineContent textInline = createRunInline(run, runText);
                if (run instanceof XWPFHyperlinkRun) {
                    XWPFHyperlink hyperlink = document.getHyperlinkByID(((XWPFHyperlinkRun) run).getHyperlinkId());
                    if (hyperlink != null) {
                        textInline = new LinkInline(Collections.singletonList(textInline),
                            hyperlink.getURL(), null);
                    }
                }
                content.add(textInline);
            }
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            if (pictures != null && !pictures.isEmpty()) {
                for (XWPFPicture picture : pictures) {
                    String imageUrl = processImage(picture, documentImagePublisher);
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        content.add(new ImageInline(imageUrl, "Image", null));
                    }
                }
            }
        }
        if (content.isEmpty()) return null;
        int headingLevel = headingLevel(paragraph);
        return headingLevel > 0 ? new HeadingBlock(content, headingLevel) : new ParagraphBlock(content);
    }

    /**
     * 将 Word run 转换为保留字符样式的行内内容。
     */
    private InlineContent createRunInline(XWPFRun run, String text) {
        boolean code = (run.getFontFamily() != null && run.getFontFamily().toLowerCase(Locale.ROOT).contains("mono"))
            || (run.getStyle() != null && run.getStyle().toLowerCase(Locale.ROOT).contains("code"));
        if (run.isBold() || run.isItalic() || run.isStrikeThrough() || code) {
            return new StyledTextInline(text, run.isBold(), run.isItalic(), run.isStrikeThrough(), code);
        }
        return new TextInline(text);
    }

    /**
     * 根据段落样式识别 Heading 1 至 Heading 6。
     */
    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) return 0;
        String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.startsWith("heading")) {
            try {
                return Math.max(1, Math.min(6, Integer.parseInt(normalized.substring(7))));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 判断编号段落使用数字编号还是项目符号。
     */
    private boolean isOrderedList(XWPFParagraph paragraph, XWPFDocument document) {
        try {
            XWPFNum num = document.getNumbering().getNum(paragraph.getNumID());
            BigInteger abstractId = num.getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abstractNum = document.getNumbering().getAbstractNum(abstractId);
            int level = paragraph.getNumIlvl() == null ? 0 : paragraph.getNumIlvl().intValue();
            String format = abstractNum.getCTAbstractNum().getLvlArray(level).getNumFmt().getVal().toString();
            return !"bullet".equalsIgnoreCase(format) && !"none".equalsIgnoreCase(format);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 提取单元格文本，递归处理段落和图片
     *
     * @param cell                   Word 表格单元格
     * @param documentImagePublisher 图片发布器
     * @return 去除首尾空白后的单元格文本
     * @throws IOException 图片发布失败
     */
    private List<DocumentBlock> getCellContent(XWPFTableCell cell, XWPFDocument document,
                                               DocumentImagePublisher documentImagePublisher) throws IOException {
        List<DocumentBlock> content = new java.util.ArrayList<>();
        for (IBodyElement element : cell.getBodyElements()) {
            if (element instanceof XWPFParagraph) {
                XWPFParagraph paragraph = (XWPFParagraph) element;
                DocumentBlock block = extractParagraphBlock(paragraph, document, documentImagePublisher);
                if (block != null) {
                    if (paragraph.getNumID() != null) {
                        content.add(new ListBlock(isOrderedList(paragraph, document))
                            .addItem(new ListItem().addBlock(block)));
                    } else content.add(block);
                }
            } else if (element instanceof XWPFTable) {
                content.add(extractStructuredTable((XWPFTable) element, document, documentImagePublisher));
            }
        }
        return content;
    }

    /**
     * 将 Word 表格转换为稀疏结构化表格。
     *
     * <p>{@code logicalColumn} 表示考虑横向合并后的逻辑列坐标；纵向合并起始单元格会被
     * 记录到 {@code verticalMerges}，后续 continue 单元格只增加锚点的 {@code rowSpan}，
     * 不再生成新的单元格对象。</p>
     *
     * @param table                  Word 表格
     * @param documentImagePublisher 图片发布器
     * @return 保留横向和纵向合并信息的表格
     * @throws IOException 单元格图片发布失败
     */
    private TableBlock extractStructuredTable(XWPFTable table, XWPFDocument document,
                                              DocumentImagePublisher documentImagePublisher) throws IOException {
        TableBlock result = new TableBlock();
        Map<Integer, TableCell> verticalMerges = new HashMap<>();
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null) return result;

        for (XWPFTableRow sourceRow : rows) {
            TableRow row = new TableRow();
            int logicalColumn = 0;
            for (XWPFTableCell sourceCell : sourceRow.getTableCells()) {
                int colSpan = getGridSpan(sourceCell);
                VerticalMerge merge = getVerticalMerge(sourceCell);
                if (merge == VerticalMerge.CONTINUE) {
                    TableCell anchor = verticalMerges.get(logicalColumn);
                    if (anchor != null) anchor.incrementRowSpan();
                    logicalColumn += colSpan;
                    continue;
                }

                for (int column = logicalColumn; column < logicalColumn + colSpan; column++) {
                    verticalMerges.remove(column);
                }
                TableCell cell = new TableCell(getCellContent(sourceCell, document, documentImagePublisher),
                    logicalColumn, 1, colSpan, sourceRow.isRepeatHeader());
                row.addCell(cell);
                if (merge == VerticalMerge.RESTART) {
                    for (int column = logicalColumn; column < logicalColumn + colSpan; column++) {
                        verticalMerges.put(column, cell);
                    }
                }
                logicalColumn += colSpan;
            }
            result.addRow(row);
        }
        return result;
    }

    /**
     * 读取 Word 单元格的横向网格跨度。
     *
     * @param cell Word 单元格
     * @return {@code gridSpan} 值；未设置或非法时返回 1
     */
    private int getGridSpan(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || !properties.isSetGridSpan()
            || properties.getGridSpan().getVal() == null) return 1;
        return Math.max(1, properties.getGridSpan().getVal().intValue());
    }

    /**
     * 解析 Word 单元格的纵向合并状态。
     *
     * @param cell Word 单元格
     * @return 未合并、合并起始或合并继续状态
     */
    private VerticalMerge getVerticalMerge(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || !properties.isSetVMerge()) return VerticalMerge.NONE;
        CTVMerge merge = properties.getVMerge();
        if (merge.getVal() != null && "restart".equalsIgnoreCase(merge.getVal().toString())) {
            return VerticalMerge.RESTART;
        }
        return VerticalMerge.CONTINUE;
    }

    /**
     * WordprocessingML 纵向合并标记的内部语义。
     */
    private enum VerticalMerge {
        /**
         * 单元格未参与纵向合并。
         */
        NONE,
        /**
         * 单元格是纵向合并区域的起始锚点。
         */
        RESTART,
        /**
         * 单元格继续上方已经开始的纵向合并。
         */
        CONTINUE
    }

    /**
     * 发布单个嵌入图片并返回图片地址。
     *
     * @param picture                Word 图片对象
     * @param documentImagePublisher 图片发布器
     * @return 发布后的图片地址；图片数据为空时返回 {@code null}
     * @throws IOException 图片读取或发布失败
     */
    private String processImage(XWPFPicture picture, DocumentImagePublisher documentImagePublisher) throws IOException {
        XWPFPictureData pictureData = picture.getPictureData();
        if (pictureData == null) {
            return null;
        }


        String mimeType = pictureData.getPackagePart().getContentType();
        if (mimeType == null || mimeType.trim().isEmpty()) {
            mimeType = "application/octet-stream";
        }

        byte[] data = pictureData.getData();
        if (data == null || data.length == 0) {
            return null;
        }
        return MarkdownFormatter.handleImage(documentImagePublisher, data, mimeType,
            pictureData.getFileName());
    }

    /**
     * 返回解析器优先级。
     *
     * @return 优先级 10，确保专用 DOCX 解析器优先于通用 Tika 解析器
     */
    @Override
    public int getOrder() {
        return 10;
    }

}
