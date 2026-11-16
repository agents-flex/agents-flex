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

import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.extractor.StructuredDocumentExtractor;
import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TableCell;
import com.agentsflex.doc.model.TableRow;
import com.agentsflex.doc.model.TextBlock;
import com.agentsflex.doc.source.DocumentSource;
import com.agentsflex.doc.util.EncodingDetectUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 增强版 HTML 文档提取器
 * 支持可配置的噪音过滤规则（含中文网站常见广告）
 */
public class HtmlExtractor implements StructuredDocumentExtractor {

    /**
     * 支持的 HTML MIME 类型集合。
     */
    private static final Set<String> SUPPORTED_MIME_TYPES;

    /**
     * 支持的 HTML 文件扩展名集合。
     */
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

    /**
     * 默认移除的网页噪音 CSS 选择器，例如脚本、导航、广告和弹窗。
     */
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

    /**
     * 用于识别广告、侧栏、推荐、分享和弹窗等噪音节点的 class/id 关键词。
     */
    private static final Set<String> CLASS_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ad", "adv", "advertisement", "banner", "sponsor",
        "sidebar", "sider", "widget", "module", "recommend",
        "related", "similar", "youlike", "hot", "tuijian",
        "share", "social", "like", "follow", "subscribe",
        "cookie", "consent", "popup", "modal", "dialog",
        "footer", "nav", "breadcrumb", "pager", "pagination"
    )));

    /**
     * 对 class/id 进行单词边界匹配的噪音正则表达式。
     */
    private static final Pattern ID_CLASS_PATTERN = Pattern.compile("(?i)\\b(" +
        String.join("|",
            "ad", "adv", "advertisement", "banner", "sponsor",
            "sidebar", "sider", "widget", "module", "tuijian",
            "share", "social", "like", "follow", "subscribe",
            "cookie", "consent", "popup", "modal", "dialog",
            "footer", "nav", "breadcrumb", "pager", "pagination"
        ) + ")\\b"
    );

    /**
     * 运行期动态添加的自定义噪音 CSS 选择器。
     */
    private static final Set<String> CUSTOM_SELECTORS = ConcurrentHashMap.newKeySet();

    /**
     * 运行期动态添加的自定义 class/id 噪音关键词。
     */
    private static final Set<String> CUSTOM_CLASS_KEYWORDS = ConcurrentHashMap.newKeySet();

    /**
     * 添加自定义噪音选择器（CSS 选择器）
     *
     * @param selector Jsoup 支持的 CSS 选择器
     */
    public static void addCustomSelector(String selector) {
        CUSTOM_SELECTORS.add(selector);
    }

    /**
     * 添加自定义 class/id 关键词
     *
     * @param keyword 要忽略大小写匹配的关键词
     */
    public static void addCustomKeyword(String keyword) {
        CUSTOM_CLASS_KEYWORDS.add(keyword.toLowerCase());
    }

    /**
     * 判断输入源是否为 HTML、XHTML 或相关文件格式。
     *
     * @param source 文档输入源
     * @return 支持该输入源时返回 {@code true}
     */
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

    /**
     * 将 HTML 文档解析为结构化文档模型。
     *
     * <p>解析前会移除已知网页噪音。标题、段落和列表项转换为文本块；表格保留
     * {@code th/td} 语义及 {@code rowspan/colspan}。源 HTML 不会直接透传。</p>
     *
     * @param source                 HTML 文档输入源
     * @param documentImagePublisher 图片发布器；当前 HTML 解析不发布内嵌图片
     * @return 结构化文档
     * @throws IOException 文档读取或 HTML 解析失败
     */
    @Override
    public ExtractedDocument extractDocument(DocumentSource source,
                                             DocumentImagePublisher documentImagePublisher) throws IOException {
        try (InputStream is = source.openStream()) {
            String html = EncodingDetectUtil.readToString(is);
            if (html.trim().isEmpty()) {
                return new ExtractedDocument();
            }

            Document doc = Jsoup.parse(html);
            doc.outputSettings().prettyPrint(false);

            ExtractedDocument extracted = new ExtractedDocument();
            extractTitle(doc, extracted);
            extractBodyContent(doc, extracted);
            return extracted;
        } catch (Exception e) {
            throw new IOException("Failed to parse HTML: " + e.getMessage(), e);
        }
    }

    /**
     * 提取 HTML {@code title} 并作为一级标题追加。
     *
     * @param doc       Jsoup 文档
     * @param extracted 目标结构化文档
     */
    private void extractTitle(Document doc, ExtractedDocument extracted) {
        Elements titleEl = doc.select("title");
        if (!titleEl.isEmpty()) {
            String title = titleEl.first().text().trim();
            if (!title.isEmpty()) {
                extracted.addBlock(TextBlock.heading(title, 1));
            }
        }
    }

    /**
     * 清理网页噪音并按 DOM 顺序提取 body 内容。
     *
     * @param doc       Jsoup 文档
     * @param extracted 目标结构化文档
     */
    private void extractBodyContent(Document doc, ExtractedDocument extracted) {
        Element body = doc.body();
        if (body == null) return;

        // 1. 移除已知噪音元素（CSS 选择器）
        removeElementsBySelectors(body);

        // 2. 移除 class/id 包含关键词的元素
        removeElementsWithKeywords(body);

        // 3. 遍历剩余节点
        for (Node node : body.childNodes()) {
            appendNode(node, extracted);
        }
    }

    /**
     * 将单个 DOM 节点转换为一个或多个结构化块。
     *
     * <p>容器节点递归处理子节点；遇到表格时整体转换为一个表格块，避免将表格内部文本
     * 重复提取为普通段落。</p>
     *
     * @param node      当前 DOM 节点
     * @param extracted 目标结构化文档
     */
    private void appendNode(Node node, ExtractedDocument extracted) {
        if (!(node instanceof Element)) {
            String text = node instanceof org.jsoup.nodes.TextNode
                ? ((org.jsoup.nodes.TextNode) node).text().trim() : "";
            if (!text.isEmpty()) extracted.addBlock(TextBlock.paragraph(text));
            return;
        }
        Element element = (Element) node;
        String tagName = element.tagName().toLowerCase();
        if (tagName.matches("h[1-6]")) {
            extracted.addBlock(TextBlock.heading(element.text().trim(),
                Integer.parseInt(tagName.substring(1))));
        } else if ("p".equals(tagName)) {
            String text = element.text().trim();
            if (!text.isEmpty()) extracted.addBlock(TextBlock.paragraph(text));
        } else if ("li".equals(tagName)) {
            String text = element.text().trim();
            if (!text.isEmpty()) extracted.addBlock(TextBlock.listItem(text));
        } else if ("table".equals(tagName)) {
            TableBlock table = extractStructuredTable(element);
            if (!table.isEmpty()) extracted.addBlock(table);
        } else {
            for (Node child : element.childNodes()) appendNode(child, extracted);
        }
    }

    /**
     * 使用 CSS 选择器移除噪音
     *
     * @param body HTML body 元素
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
     *
     * @param body HTML body 元素
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


    /**
     * 将 HTML 表格转换为保留跨度的结构化表格。
     *
     * <p>{@code occupiedThroughRow} 记录上方 {@code rowspan} 在每一逻辑列持续到哪一行。
     * 处理后续行时会先跳过这些已占用列，从而为新单元格计算正确的逻辑列索引。</p>
     *
     * @param table HTML table 元素
     * @return 结构化表格
     */
    private TableBlock extractStructuredTable(Element table) {
        TableBlock result = new TableBlock();
        Map<Integer, Integer> occupiedThroughRow = new HashMap<>();
        List<Element> sourceRows = directRows(table);
        for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
            Element row = sourceRows.get(rowIndex);
            TableRow tableRow = new TableRow();
            int columnIndex = 0;
            for (Element cell : row.children()) {
                if (!"td".equalsIgnoreCase(cell.tagName())
                    && !"th".equalsIgnoreCase(cell.tagName())) continue;
                while (occupiedThroughRow.containsKey(columnIndex)
                    && occupiedThroughRow.get(columnIndex) >= rowIndex) columnIndex++;
                int rowSpan = positiveInt(cell.attr("rowspan"));
                int colSpan = positiveInt(cell.attr("colspan"));
                tableRow.addCell(new TableCell(cell.text().trim(), columnIndex,
                    rowSpan, colSpan, "th".equalsIgnoreCase(cell.tagName())));
                if (rowSpan > 1) {
                    for (int column = columnIndex; column < columnIndex + colSpan; column++) {
                        occupiedThroughRow.put(column, rowIndex + rowSpan - 1);
                    }
                }
                columnIndex += colSpan;
            }
            if (!tableRow.getCells().isEmpty()) result.addRow(tableRow);
        }
        return result;
    }

    /**
     * 获取属于当前表格的直接行，排除嵌套表格中的行。
     *
     * <p>支持行直接位于 {@code table} 下，也支持位于 {@code thead/tbody/tfoot} 下。</p>
     *
     * @param table HTML table 元素
     * @return 按 DOM 顺序排列的直接表格行
     */
    private List<Element> directRows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            if ("tr".equalsIgnoreCase(child.tagName())) rows.add(child);
            else if ("thead".equalsIgnoreCase(child.tagName())
                || "tbody".equalsIgnoreCase(child.tagName())
                || "tfoot".equalsIgnoreCase(child.tagName())) {
                for (Element row : child.children()) {
                    if ("tr".equalsIgnoreCase(row.tagName())) rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * 将 HTML 跨度属性解析为正整数。
     *
     * @param value {@code rowspan} 或 {@code colspan} 属性值
     * @return 合法正整数；缺失、非法或非正数时返回 1
     */
    private int positiveInt(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (Exception ignored) {
            return 1;
        }
    }


    /**
     * 返回解析器优先级。
     *
     * @return 优先级 12，使专用 HTML 解析器优先于通用 Tika 解析器
     */
    @Override
    public int getOrder() {
        return 12;
    }

}
