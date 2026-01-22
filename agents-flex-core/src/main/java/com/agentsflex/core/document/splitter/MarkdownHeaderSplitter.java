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
package com.agentsflex.core.document.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.id.DocumentIdGenerator;
import com.agentsflex.core.util.StringUtil;

import java.util.*;

public class MarkdownHeaderSplitter implements DocumentSplitter {

    /**
     * 最大标题级别（inclusive），用于触发拆分。
     * 例如：splitLevel = 2 表示在 # 和 ## 处拆分，### 及以下不作为新块起点。
     */
    private int splitLevel;

    /**
     * 是否在每个 chunk 中保留父级标题路径（如 "Introduction > Background"）
     */
    private boolean includeParentHeaders = true;

    public MarkdownHeaderSplitter() {
    }

    public MarkdownHeaderSplitter(int splitLevel) {
        if (splitLevel < 1 || splitLevel > 6) {
            throw new IllegalArgumentException("splitLevel must be between 1 and 6, got: " + splitLevel);
        }
        this.splitLevel = splitLevel;
    }

    public MarkdownHeaderSplitter(int splitLevel, boolean includeParentHeaders) {
        this(splitLevel);
        this.includeParentHeaders = includeParentHeaders;
    }

    public int getSplitLevel() {
        return splitLevel;
    }

    public void setSplitLevel(int splitLevel) {
        if (splitLevel < 1 || splitLevel > 6) {
            throw new IllegalArgumentException("splitLevel must be between 1 and 6");
        }
        this.splitLevel = splitLevel;
    }

    public boolean isIncludeParentHeaders() {
        return includeParentHeaders;
    }

    public void setIncludeParentHeaders(boolean includeParentHeaders) {
        this.includeParentHeaders = includeParentHeaders;
    }

    @Override
    public List<Document> split(Document document, DocumentIdGenerator idGenerator) {
        if (document == null || StringUtil.noText(document.getContent())) {
            return Collections.emptyList();
        }

        String content = document.getContent();
        String[] lines = content.split("\n");

        List<DocumentChunk> chunks = new ArrayList<>();
        Deque<HeaderInfo> headerStack = new ArrayDeque<>();

        StringBuilder currentContent = new StringBuilder();
        int currentStartLine = 0;

        boolean inCodeBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) {
                currentContent.append("\n");
                continue;
            }

            // 检测围栏代码块的开始或结束（支持 ``` 或 ~~~）
            String trimmedLine = stripLeading(line);
            if (trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock;
                currentContent.append(line).append("\n");
                continue;
            }

            if (!inCodeBlock) {
                HeaderInfo header = parseHeader(line);
                if (header != null && header.level <= splitLevel) {
                    // 触发新 chunk
                    if (currentContent.length() > 0 || !chunks.isEmpty()) {
                        flushChunk(chunks, currentContent.toString(), headerStack, currentStartLine, i - 1, document);
                        currentContent.setLength(0);
                    }
                    currentStartLine = i;

                    // 弹出栈中层级大于等于当前的标题
                    while (!headerStack.isEmpty() && headerStack.peek().level >= header.level) {
                        headerStack.pop();
                    }
                    headerStack.push(header);

                    // 将标题行加入当前内容（保留结构）
                    currentContent.append(line).append("\n");
                    continue;
                }
            }

            // 普通文本行或代码块内行
            currentContent.append(line).append("\n");
        }

        // Flush remaining content
        if (currentContent.length() > 0) {
            flushChunk(chunks, currentContent.toString(), headerStack, currentStartLine, lines.length - 1, document);
        }

        // 构建结果 Document 列表
        List<Document> result = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            Document doc = new Document();
            doc.setContent(chunk.content.trim());
            doc.addMetadata(document.getMetadataMap());

            if (includeParentHeaders && !chunk.headerPath.isEmpty()) {
                doc.addMetadata("header_path", String.join(" > ", chunk.headerPath));
            }
            doc.addMetadata("start_line", String.valueOf(chunk.startLine));
            doc.addMetadata("end_line", String.valueOf(chunk.endLine));

            if (idGenerator != null) {
                doc.setId(idGenerator.generateId(doc));
            }
            result.add(doc);
        }

        return result;
    }

    private void flushChunk(List<DocumentChunk> chunks, String content,
                            Deque<HeaderInfo> headerStack, int startLine, int endLine, Document sourceDoc) {
        if (StringUtil.noText(content.trim())) {
            return;
        }

        // 从根到当前构建标题路径
        List<String> headerPath = new ArrayList<>();
        List<HeaderInfo> stackCopy = new ArrayList<>(headerStack);
        Collections.reverse(stackCopy);
        for (HeaderInfo h : stackCopy) {
            headerPath.add(h.text);
        }

        chunks.add(new DocumentChunk(content, headerPath, startLine, endLine));
    }

    /**
     * 解析一行是否为合法的 ATX 标题（# Title）
     *
     * @param line 输入行
     * @return HeaderInfo 或 null
     */
    private HeaderInfo parseHeader(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        line = stripLeading(line);
        if (!line.startsWith("#")) {
            return null;
        }

        int level = 0;
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            level++;
            i++;
        }

        if (level > 6) {
            return null; // 非法标题
        }

        // 必须后跟空格或行结束（符合 CommonMark 规范）
        if (i < line.length() && line.charAt(i) != ' ') {
            return null;
        }

        String text = line.substring(i).trim();
        return new HeaderInfo(level, text);
    }

    private static String stripLeading(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    // -- 内部辅助类 --

    private static class HeaderInfo {
        final int level;
        final String text;

        HeaderInfo(int level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    private static class DocumentChunk {
        final String content;
        final List<String> headerPath;
        final int startLine;
        final int endLine;

        DocumentChunk(String content, List<String> headerPath, int startLine, int endLine) {
            this.content = content;
            this.headerPath = headerPath;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}
