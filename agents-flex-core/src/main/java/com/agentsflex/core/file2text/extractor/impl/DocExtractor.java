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
package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.extractor.FileExtractor;
import com.agentsflex.core.file2text.handler.Base64ExtractedImageHandler;
import com.agentsflex.core.file2text.handler.ExtractedImageHandler;
import com.agentsflex.core.file2text.source.DocumentSource;
import com.agentsflex.core.util.ImageUtil;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * DOC 文档提取器（.doc）
 * 支持旧版 Word 97-2003 格式
 * 支持段落、Markdown 表格以及嵌入图片处理
 */
public class DocExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/msword");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("doc");
        extensions.add("dot");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType)) {
            return true;
        }

        if (fileName != null) {
            String ext = getExtension(fileName);
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        return extractText(source, new Base64ExtractedImageHandler());
    }

    @Override
    public String extractText(DocumentSource source, ExtractedImageHandler extractedImageHandler) throws IOException {
        try (InputStream is = source.openStream();
             POIFSFileSystem fs = new POIFSFileSystem(is);
             HWPFDocument doc = new HWPFDocument(fs)) {

            StringBuilder text = new StringBuilder();
            Range range = doc.getRange();
            PicturesTable picturesTable = doc.getPicturesTable();

            int numParagraphs = range.numParagraphs();
            for (int i = 0; i < numParagraphs; i++) {
                Paragraph paragraph = range.getParagraph(i);

                if (paragraph.isInTable()) {
                    Table table = range.getTable(paragraph);
                    extractTable(table, picturesTable, text, extractedImageHandler);

                    int tableEndOffset = table.getEndOffset();
                    while (i + 1 < numParagraphs
                        && range.getParagraph(i + 1).getStartOffset() < tableEndOffset) {
                        i++;
                    }
                    continue;
                }

                appendParagraphContent(paragraph, picturesTable, text, extractedImageHandler);
                text.append('\n');
            }

            return text.toString().trim();

        } catch (Exception e) {
            throw new IOException("Failed to extract .doc file: " + e.getMessage(), e);
        }
    }

    private void appendParagraphContent(Paragraph paragraph, PicturesTable picturesTable,
                                        StringBuilder text, ExtractedImageHandler extractedImageHandler) throws IOException {
        int numRuns = paragraph.numCharacterRuns();
        for (int i = 0; i < numRuns; i++) {
            CharacterRun run = paragraph.getCharacterRun(i);
            if (picturesTable.hasPicture(run)) {
                Picture picture = picturesTable.extractPicture(run, true);
                String imageUrl = processPicture(picture, extractedImageHandler);
                if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                    text.append("\n![Image](").append(imageUrl).append(")\n");
                }
            } else {
                String runText = run.text();
                if (runText != null) {
                    text.append(cleanRunText(runText));
                }
            }
        }
    }

    private String cleanRunText(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\r' || character == '\u0007') {
                continue;
            }
            if (character == '\u000B' || character == '\u000C') {
                cleaned.append('\n');
            } else if (character >= 0x20 || character == '\n' || character == '\t') {
                cleaned.append(character);
            }
        }
        return cleaned.toString();
    }

    private void extractTable(Table table, PicturesTable picturesTable, StringBuilder text,
                              ExtractedImageHandler extractedImageHandler) throws IOException {
        int rowCount = table.numRows();
        if (rowCount == 0) {
            return;
        }

        int columnCount = 0;
        for (int row = 0; row < rowCount; row++) {
            columnCount = Math.max(columnCount, table.getRow(row).numCells());
        }
        if (columnCount == 0) {
            return;
        }

        text.append('\n');
        appendTableRow(table.getRow(0), columnCount, picturesTable, text, extractedImageHandler);
        text.append('|');
        for (int column = 0; column < columnCount; column++) {
            text.append(" --- |");
        }
        text.append('\n');

        for (int row = 1; row < rowCount; row++) {
            appendTableRow(table.getRow(row), columnCount, picturesTable, text, extractedImageHandler);
        }
        text.append('\n');
    }

    private void appendTableRow(TableRow row, int columnCount,
                                PicturesTable picturesTable, StringBuilder text,
                                ExtractedImageHandler extractedImageHandler) throws IOException {
        text.append('|');
        for (int column = 0; column < columnCount; column++) {
            String value = column < row.numCells()
                ? getCellText(row.getCell(column), picturesTable, extractedImageHandler)
                : "";
            text.append(' ').append(escapeMarkdownCell(value)).append(" |");
        }
        text.append('\n');
    }

    private String getCellText(TableCell cell, PicturesTable picturesTable,
                               ExtractedImageHandler extractedImageHandler) throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < cell.numParagraphs(); i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            appendParagraphContent(cell.getParagraph(i), picturesTable, text, extractedImageHandler);
        }
        return text.toString().trim();
    }

    private String escapeMarkdownCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim();
    }

    private String processPicture(Picture picture, ExtractedImageHandler extractedImageHandler) throws IOException {
        if (picture == null) {
            return null;
        }

        byte[] data = picture.getContent();
        if (data == null || data.length == 0) {
            return null;
        }

        String ext = picture.suggestFileExtension();
        String mimeType = getPictureMimeType(ext);
        return extractedImageHandler.handle(data, mimeType, picture.suggestFullFileName());
    }

    private String getPictureMimeType(String extension) {
        if ("wmf".equalsIgnoreCase(extension)) {
            return "image/x-wmf";
        }
        if ("emf".equalsIgnoreCase(extension)) {
            return "image/x-emf";
        }

        String mimeType = ImageUtil.getMimeTypeFromExtension(extension);
        return mimeType != null ? mimeType : "application/octet-stream";
    }


    @Override
    public int getOrder() {
        return 15; // 低于 .docx
    }

}
