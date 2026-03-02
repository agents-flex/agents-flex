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
import com.agentsflex.core.file2text.source.DocumentSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 增强版 HTML 文档提取器
 * 支持可配置的噪音过滤规则（含中文网站常见广告）
 */
public class HtmlExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("text/html");
        mimeTypes.add("application/xhtml+xml");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("html");
        extensions.add("htm");
        extensions.add("xhtml");
        extensions.add("mhtml");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    // 噪音过滤规则
    private static final Set<String> DEFAULT_SELECTORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "script", "style", "noscript",
        "nav", "header", "footer", "aside",
        "iframe", "embed", "object", "video", "audio",
        ".ads", ".advertisement", ".ad-", "ad:",
        ".sidebar", ".sider", ".widget", ".module",
        ".breadcrumb", ".pager", ".pagination",
        ".share", ".social", ".like", ".subscribe",
        ".cookie", ".consent", ".banner", ".popup",
        "[data-ad]", "[data-testid*='ad']", "[data-type='advertisement']"
    )));

    private static final Set<String> CLASS_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ad", "adv", "advertisement", "banner", "sponsor",
        "sidebar", "sider", "widget", "module", "recommend",
        "related", "similar", "youlike", "hot", "tuijian",
        "share", "social", "like", "follow", "subscribe",
        "cookie", "consent", "popup", "modal", "dialog",
        "footer", "nav", "breadcrumb", "pager", "pagination"
    )));

    private static final Pattern ID_CLASS_PATTERN = Pattern.compile("(?i)\\b(" +
        String.join("|",
            "ad", "adv", "advertisement", "banner", "sponsor",
            "sidebar", "sider", "widget", "module", "tuijian",
            "share", "social", "like", "follow", "subscribe",
            "cookie", "consent", "popup", "modal", "dialog",
            "footer", "nav", "breadcrumb", "pager", "pagination"
        ) + ")\\b"
    );

    // 可动态添加的自定义规则
    private static final Set<String> CUSTOM_SELECTORS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CUSTOM_CLASS_KEYWORDS = ConcurrentHashMap.newKeySet();

    /**
     * 添加自定义噪音选择器（CSS 选择器）
     */
    public static void addCustomSelector(String selector) {
        CUSTOM_SELECTORS.add(selector);
    }

    /**
     * 添加自定义 class/id 关键词
     */
    public static void addCustomKeyword(String keyword) {
        CUSTOM_CLASS_KEYWORDS.add(keyword.toLowerCase());
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase())) {
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
        try (InputStream is = source.openStream()) {
            String html = readToString(is, StandardCharsets.UTF_8);
            if (html.trim().isEmpty()) {
                return "";
            }

            Document doc = Jsoup.parse(html);
            doc.outputSettings().prettyPrint(false);

            StringBuilder text = new StringBuilder();

            extractTitle(doc, text);
            extractBodyContent(doc, text);

            return text.toString().trim();
        } catch (Exception e) {
            throw new IOException("Failed to parse HTML: " + e.getMessage(), e);
        }
    }

    private void extractTitle(Document doc, StringBuilder text) {
        Elements titleEl = doc.select("title");
        if (!titleEl.isEmpty()) {
            String title = titleEl.first().text().trim();
            if (!title.isEmpty()) {
                text.append(title).append("\n\n");
            }
        }
    }

    private void extractBodyContent(Document doc, StringBuilder text) {
        Element body = doc.body();
        if (body == null) return;

        // 1. 移除已知噪音元素（CSS 选择器）
        removeElementsBySelectors(body);

        // 2. 移除 class/id 包含关键词的元素
        removeElementsWithKeywords(body);

        // 3. 遍历剩余节点
        for (Node node : body.childNodes()) {
            appendNodeText(node, text, 0);
        }
    }

    /**
     * 使用 CSS 选择器移除噪音
     */
    private void removeElementsBySelectors(Element body) {
        List<String> allSelectors = new ArrayList<>(DEFAULT_SELECTORS);
        allSelectors.addAll(CUSTOM_SELECTORS);

        for (String selector : allSelectors) {
            try {
                body.select(selector).remove();
            } catch (Exception e) {
                // 忽略无效选择器
            }
        }
    }

    /**
     * 移除 class 或 id 包含关键词的元素
     */
    private void removeElementsWithKeywords(Element body) {
        // 合并默认和自定义关键词
        Set<String> keywords = new HashSet<>(CLASS_KEYWORDS);
        keywords.addAll(CUSTOM_CLASS_KEYWORDS);

        // 使用 DFS 遍历所有元素
        Deque<Element> stack = new ArrayDeque<>();
        stack.push(body);

        while (!stack.isEmpty()) {
            Element el = stack.pop();

            // 检查 class 或 id 是否匹配
            String classes = el.className().toLowerCase();
            String id = el.id().toLowerCase();

            for (String keyword : keywords) {
                if (classes.contains(keyword) || id.contains(keyword)) {
                    el.remove(); // 移除整个元素
                    break;
                }
            }

            // 匹配正则模式
            if (ID_CLASS_PATTERN.matcher(classes).find() ||
                ID_CLASS_PATTERN.matcher(id).find()) {
                el.remove();
                continue;
            }

            // 将子元素加入栈
            for (Element child : el.children()) {
                stack.push(child);
            }
        }
    }


    private String repeat(String string, int times) {
        if (times <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(string);
        }
        return sb.toString();
    }

    // 节点文本提取
    private void appendNodeText(Node node, StringBuilder text, int level) {
        if (node == null) return;

        if (node instanceof org.jsoup.nodes.TextNode) {
            String txt = ((org.jsoup.nodes.TextNode) node).text().trim();
            if (!txt.isEmpty()) {
                text.append(txt).append(" ");
            }
        } else if (node instanceof Element) {
            Element el = (Element) node;
            String tagName = el.tagName().toLowerCase();

            if (tagName.matches("h[1-6]")) {
                text.append("\n")
                    .append(repeat("##", Integer.parseInt(tagName.substring(1))))
                    .append(el.text().trim())
                    .append("\n\n");
            } else if (tagName.equals("p")) {
                String paraText = el.text().trim();
                if (!paraText.isEmpty()) {
                    text.append(paraText).append("\n\n");
                }
            } else if (tagName.equals("li")) {
                text.append("- ").append(el.text().trim()).append("\n");
            } else if (tagName.equals("table")) {
                extractTable(el, text);
                text.append("\n");
            } else if (tagName.equals("br")) {
                text.append("\n");
            } else if (tagName.equals("a")) {
                String href = el.attr("href");
                String textPart = el.text().trim();
                if (!textPart.isEmpty()) {
                    text.append(textPart);
                    if (!href.isEmpty() && !href.equals(textPart)) {
                        text.append(" [").append(href).append("]");
                    }
                    text.append(" ");
                }
            } else if (isBlockLevel(tagName)) {
                text.append("\n");
                for (Node child : el.childNodes()) {
                    appendNodeText(child, text, level + 1);
                }
                text.append("\n");
            } else {
                for (Node child : el.childNodes()) {
                    appendNodeText(child, text, level);
                }
            }
        }
    }

    private boolean isBlockLevel(String tagName) {
        Set<String> blockTags = new HashSet<>(Arrays.asList(
            "div", "p", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "table", "tr", "td", "th",
            "blockquote", "pre", "section", "article", "figure"
        ));
        return blockTags.contains(tagName);
    }

    private void extractTable(Element table, StringBuilder text) {
        text.append("[Table Start]\n");
        Elements rows = table.select("tr");
        for (Element row : rows) {
            Elements cells = row.select("td, th");
            List<String> cellTexts = new ArrayList<>();
            for (Element cell : cells) {
                cellTexts.add(cell.text().trim());
            }
            text.append(String.join(" | ", cellTexts)).append("\n");
        }
        text.append("[Table End]\n");
    }

    private String readToString(InputStream is, java.nio.charset.Charset charset) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(is, charset);
             java.io.BufferedReader br = new java.io.BufferedReader(reader)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = br.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }

    @Override
    public int getOrder() {
        return 12;
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        int lastDot = fileName.lastIndexOf('.');
        return fileName.substring(lastDot + 1);
    }
}
