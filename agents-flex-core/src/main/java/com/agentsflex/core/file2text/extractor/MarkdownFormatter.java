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
package com.agentsflex.core.file2text.extractor;

import com.agentsflex.core.file2text.handler.ExtractedImageHandler;

import java.io.IOException;
import java.util.List;

/**
 * FileExtractor 共享的 Markdown 图片和表格格式化工具。
 */
public final class MarkdownFormatter {

    private static final String DEFAULT_IMAGE_MIME_TYPE = "application/octet-stream";
    private static final String DEFAULT_IMAGE_FILE_NAME = "embedded-image";

    private MarkdownFormatter() {
    }

    public static String handleImage(ExtractedImageHandler handler, byte[] imageBytes,
                                     String mimeType, String fileName) throws IOException {
        if (handler == null) {
            throw new IllegalArgumentException("ExtractedImageHandler cannot be null");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }

        return handler.handle(imageBytes, valueOrDefault(mimeType, DEFAULT_IMAGE_MIME_TYPE),
            valueOrDefault(fileName, DEFAULT_IMAGE_FILE_NAME));
    }

    public static void appendImage(StringBuilder output, ExtractedImageHandler handler,
                                   byte[] imageBytes, String mimeType, String fileName)
        throws IOException {
        appendImage(output, handleImage(handler, imageBytes, mimeType, fileName));
    }

    public static void appendImage(StringBuilder output, String imageUrl) {
        String markdown = formatImage(imageUrl);
        if (markdown.isEmpty()) {
            return;
        }
        startBlock(output);
        output.append(markdown).append('\n');
    }

    public static String formatImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return "";
        }
        return "![Image](" + imageUrl + ")";
    }

    public static void appendTable(StringBuilder output,
                                   List<? extends List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        int columnCount = 0;
        for (List<String> row : rows) {
            if (row != null) {
                columnCount = Math.max(columnCount, row.size());
            }
        }
        if (columnCount == 0) {
            return;
        }

        startBlock(output);
        appendTableRow(output, rows.get(0), columnCount);
        output.append('|');
        for (int column = 0; column < columnCount; column++) {
            output.append(" --- |");
        }
        output.append('\n');

        for (int row = 1; row < rows.size(); row++) {
            appendTableRow(output, rows.get(row), columnCount);
        }
        output.append('\n');
    }

    public static String escapeTableCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim();
    }

    private static void appendTableRow(StringBuilder output, List<String> row,
                                       int columnCount) {
        output.append('|');
        for (int column = 0; column < columnCount; column++) {
            String value = row != null && column < row.size() ? row.get(column) : "";
            output.append(' ').append(escapeTableCell(value)).append(" |");
        }
        output.append('\n');
    }

    private static void startBlock(StringBuilder output) {
        if (output.length() == 0) {
            return;
        }
        if (output.charAt(output.length() - 1) != '\n') {
            output.append('\n');
        }
        if (output.length() < 2 || output.charAt(output.length() - 2) != '\n') {
            output.append('\n');
        }
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }
}
