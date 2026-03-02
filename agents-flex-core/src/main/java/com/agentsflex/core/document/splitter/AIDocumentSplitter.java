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
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AIDocumentSplitter：基于大模型（AI/LLM）的语义文档拆分器。
 * 使用 "---" 作为段落分隔符，避免 JSON 解析风险。
 * 支持注入 fallback 拆分器以提高鲁棒性。
 */
public class AIDocumentSplitter implements DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(AIDocumentSplitter.class);

    private static final String DEFAULT_SPLIT_PROMPT_TEMPLATE =
        "你是一个专业的文档处理助手，请将以下长文档按语义拆分为多个逻辑连贯的段落块。\n" +
            "要求：\n" +
            "1. 每个块应保持主题/语义完整性，避免在句子中间切断。\n" +
            "2. 每个块长度建议在 200-500 字之间（可根据内容灵活调整）。\n" +
            "3. **不要添加任何解释、编号、前缀或后缀**。\n" +
            "4. **仅用三连短横线 \"---\" 作为块之间的分隔符**，格式如下：\n" +
            "\n" +
            "块1内容\n" +
            "---\n" +
            "块2内容\n" +
            "---\n" +
            "块3内容\n" +
            "\n" +
            "注意：开头不要有 ---，结尾也不要有多余的 ---。\n" +
            "\n" +
            "文档内容如下：\n" +
            "{document}";

    private static final String CHUNK_SEPARATOR = "---";

    private final ChatModel chatModel;
    private String splitPromptTemplate = DEFAULT_SPLIT_PROMPT_TEMPLATE;
    private ChatOptions chatOptions = new ChatOptions.Builder().temperature(0.2f).build();
    private int maxChunks = 20;
    private int maxTotalLength = 10000;

    // 可配置的 fallback 拆分器
    private DocumentSplitter fallbackSplitter;

    public AIDocumentSplitter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public List<Document> split(Document document, DocumentIdGenerator idGenerator) {
        if (document == null || document.getContent() == null || document.getContent().trim().isEmpty()) {
            return Collections.emptyList();
        }

        String content = document.getContent().trim();
        if (content.length() > maxTotalLength) {
            log.warn("文档过长（{} 字符），已截断至 {} 字符", content.length(), maxTotalLength);
            content = content.substring(0, maxTotalLength);
        }

        List<String> chunks;
        try {
            String prompt = splitPromptTemplate.replace("{document}", content);
            String llmOutput = chatModel.chat(prompt, chatOptions);

            chunks = parseChunksBySeparator(llmOutput, CHUNK_SEPARATOR);
        } catch (Exception e) {
            log.error("AI 拆分失败，使用 fallback 拆分器", e);
            if (fallbackSplitter == null) {
                log.error("没有可用的 fallback 拆分器，请检查配置");
                return Collections.emptyList();
            }
            List<Document> fallbackDocs = fallbackSplitter.split(document, idGenerator);
            if (fallbackDocs.size() > maxChunks) {
                return new ArrayList<>(fallbackDocs.subList(0, maxChunks));
            }
            return fallbackDocs;
        }

        List<String> validChunks = chunks.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .limit(maxChunks)
            .collect(Collectors.toList());

        List<Document> result = new ArrayList<>();
        for (String chunk : validChunks) {
            Document doc = new Document();
            doc.setContent(chunk);
            doc.setTitle(document.getTitle());
            if (idGenerator != null) {
                doc.setId(idGenerator.generateId(doc));
            }
            result.add(doc);
        }

        return result;
    }

    private List<String> parseChunksBySeparator(String text, String separator) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = text.split(separator, -1);
        List<String> chunks = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                chunks.add(trimmed);
            }
        }

        if (chunks.size() == 1 && text.contains(separator)) {
            return tryAlternativeSplit(text, separator);
        }

        return chunks;
    }

    private List<String> tryAlternativeSplit(String text, String separator) {
        String normalized = text.replaceAll("\\s*---\\s*", "---");
        return parseChunksBySeparator(normalized, separator);
    }

    // ===== Getters & Setters =====

    public void setFallbackSplitter(DocumentSplitter fallbackSplitter) {
        this.fallbackSplitter = fallbackSplitter;
    }

    public void setSplitPromptTemplate(String splitPromptTemplate) {
        if (splitPromptTemplate != null && !splitPromptTemplate.trim().isEmpty()) {
            this.splitPromptTemplate = splitPromptTemplate;
        }
    }

    public void setChatOptions(ChatOptions chatOptions) {
        if (chatOptions != null) {
            this.chatOptions = chatOptions;
        }
    }

    public void setMaxChunks(int maxChunks) {
        this.maxChunks = Math.max(1, maxChunks);
    }

    public void setMaxTotalLength(int maxTotalLength) {
        this.maxTotalLength = Math.max(100, maxTotalLength);
    }
}
