/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.doc.extractor.impl;

import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.extractor.StructuredDocumentExtractor;
import com.agentsflex.doc.model.*;
import com.agentsflex.doc.source.DocumentSource;
import com.agentsflex.doc.util.EncodingDetectUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将 HTML/XHTML 文档转换为安全、格式无关的结构化块模型。
 *
 * <p>解析器会清除脚本、样式、导航和广告等噪音节点，并保留标题、段落、行内样式、
 * 链接、图片、列表、引用、代码块、分隔线和可嵌套表格。源 HTML 标签和属性不会直接
 * 进入输出，最终安全策略由模型和渲染器共同保证。</p>
 */
public class HtmlExtractor implements StructuredDocumentExtractor {
    /**
     * 支持的 MIME 类型。
     */
    private static final Set<String> SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("text/html", "application/xhtml+xml")));
    /**
     * 支持的文件扩展名。
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("html", "htm", "xhtml", "mhtml")));
    /**
     * 默认噪音选择器。
     */
    private static final Set<String> DEFAULT_SELECTORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "script", "style", "noscript", "nav", "header", "footer", "aside", "iframe", "embed",
        "object", "video", "audio", ".ads", ".advertisement", ".sidebar", ".breadcrumb",
        ".pagination", ".share", ".social", ".cookie", ".consent", ".popup", "[data-ad]",
        "[data-testid*='ad']", "[data-type='advertisement']"
    )));
    /**
     * 默认噪音 class/id 词。
     */
    private static final Set<String> CLASS_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ad", "ads", "adv", "advertisement", "banner", "sponsor", "sidebar", "widget",
        "recommend", "related", "share", "social", "subscribe", "cookie", "consent", "popup",
        "modal", "footer", "nav", "breadcrumb", "pagination"
    )));
    /**
     * 运行期自定义噪音选择器。
     */
    private static final Set<String> CUSTOM_SELECTORS = ConcurrentHashMap.newKeySet();
    /**
     * 运行期自定义噪音关键词。
     */
    private static final Set<String> CUSTOM_CLASS_KEYWORDS = ConcurrentHashMap.newKeySet();
    /**
     * 可独立形成文档块的标签。
     */
    private static final Set<String> BLOCK_TAGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "p", "div", "section", "article", "main", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "blockquote", "pre", "table", "hr", "figure"
    )));

    /**
     * 添加自定义噪音 CSS 选择器。
     *
     * @param selector Jsoup 支持的 CSS 选择器；空值会被忽略
     */
    public static void addCustomSelector(String selector) {
        if (selector != null && !selector.trim().isEmpty()) CUSTOM_SELECTORS.add(selector.trim());
    }

    /**
     * 添加自定义 class/id 噪音关键词。
     *
     * @param keyword 关键词；空值会被忽略
     */
    public static void addCustomKeyword(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            CUSTOM_CLASS_KEYWORDS.add(keyword.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        if (mimeType != null) {
            String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED_MIME_TYPES.contains(normalized)) return true;
        }
        String fileName = source.getFileName();
        if (fileName == null) return false;
        String extension = getExtension(fileName);
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExtractedDocument extractDocument(DocumentSource source,
                                             DocumentImagePublisher documentImagePublisher) throws IOException {
        try (InputStream input = source.openStream()) {
            String html = EncodingDetectUtil.readToString(input);
            if (html.trim().isEmpty()) return new ExtractedDocument();
            Document sourceDocument = Jsoup.parse(html);
            sourceDocument.outputSettings().prettyPrint(false);
            Element body = sourceDocument.body();
            if (body == null) return new ExtractedDocument();
            removeNoise(body);

            ExtractedDocument extracted = new ExtractedDocument();
            String title = sourceDocument.title().trim();
            if (!title.isEmpty()) extracted.addBlock(new HeadingBlock(title, 1));
            for (DocumentBlock block : parseContainerBlocks(body)) extracted.addBlock(block);
            return extracted;
        } catch (Exception e) {
            throw new IOException("Failed to parse HTML: " + e.getMessage(), e);
        }
    }

    /**
     * 清除默认和自定义噪音节点。
     */
    private void removeNoise(Element body) {
        List<String> selectors = new ArrayList<>(DEFAULT_SELECTORS);
        selectors.addAll(CUSTOM_SELECTORS);
        for (String selector : selectors) {
            try {
                body.select(selector).remove();
            } catch (RuntimeException ignored) { /* 自定义选择器非法时跳过。 */ }
        }
        Set<String> keywords = new HashSet<>(CLASS_KEYWORDS);
        keywords.addAll(CUSTOM_CLASS_KEYWORDS);
        for (Element element : new ArrayList<>(body.getAllElements())) {
            if (element == body) continue;
            if (containsNoiseToken(element.classNames(), element.id(), keywords)) element.remove();
        }
    }

    /**
     * 按 class token 和 id 分词判断节点是否属于噪音。
     */
    private boolean containsNoiseToken(Set<String> classes, String id, Set<String> keywords) {
        for (String value : classes) if (keywords.contains(value.toLowerCase(Locale.ROOT))) return true;
        if (id != null) {
            for (String value : id.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (keywords.contains(value)) return true;
            }
        }
        return false;
    }

    /**
     * 将容器直接子节点转换为块，并将连续行内节点合并为一个段落。
     */
    private List<DocumentBlock> parseContainerBlocks(Element container) {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<Node> pendingInline = new ArrayList<>();
        for (Node node : container.childNodes()) {
            if (node instanceof Element && isBlockElement((Element) node)) {
                flushInlineParagraph(pendingInline, blocks);
                appendElementBlock((Element) node, blocks);
            } else pendingInline.add(node);
        }
        flushInlineParagraph(pendingInline, blocks);
        return blocks;
    }

    /**
     * 将累积的连续行内节点转换为段落。
     */
    private void flushInlineParagraph(List<Node> nodes, List<DocumentBlock> blocks) {
        if (nodes.isEmpty()) return;
        List<InlineContent> content = parseInlineNodes(nodes, InlineStyle.NONE);
        if (hasVisibleInline(content)) blocks.add(new ParagraphBlock(content));
        nodes.clear();
    }

    /**
     * 判断元素是否应作为块处理。
     */
    private boolean isBlockElement(Element element) {
        return BLOCK_TAGS.contains(element.tagName().toLowerCase(Locale.ROOT));
    }

    /**
     * 将一个块级元素追加为对应模型。
     */
    private void appendElementBlock(Element element, List<DocumentBlock> blocks) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (tag.matches("h[1-6]")) {
            List<InlineContent> content = parseInlineNodes(element.childNodes(), InlineStyle.NONE);
            if (hasVisibleInline(content)) blocks.add(new HeadingBlock(content, Integer.parseInt(tag.substring(1))));
        } else if ("p".equals(tag)) {
            List<InlineContent> content = parseInlineNodes(element.childNodes(), InlineStyle.NONE);
            if (hasVisibleInline(content)) blocks.add(new ParagraphBlock(content));
        } else if ("ul".equals(tag) || "ol".equals(tag)) blocks.add(parseList(element));
        else if ("blockquote".equals(tag)) {
            QuoteBlock quote = new QuoteBlock();
            for (DocumentBlock block : parseContainerBlocks(element)) quote.addBlock(block);
            blocks.add(quote);
        } else if ("pre".equals(tag)) blocks.add(parseCodeBlock(element));
        else if ("table".equals(tag)) blocks.add(parseTable(element));
        else if ("hr".equals(tag)) blocks.add(new HorizontalRuleBlock());
        else if ("figure".equals(tag)) appendFigure(element, blocks);
        else blocks.addAll(parseContainerBlocks(element));
    }

    /**
     * 将 figure 中的图片和说明转换为块。
     */
    private void appendFigure(Element figure, List<DocumentBlock> blocks) {
        Element image = figure.selectFirst("img");
        Element caption = figure.selectFirst("figcaption");
        if (image != null) blocks.add(new ImageBlock(image.attr("src"), image.attr("alt"), image.attr("title")));
        if (caption != null) {
            List<InlineContent> content = parseInlineNodes(caption.childNodes(), InlineStyle.NONE);
            if (hasVisibleInline(content)) blocks.add(new ParagraphBlock(content));
        }
    }

    /**
     * 解析有序或无序列表及其嵌套列表。
     */
    private ListBlock parseList(Element listElement) {
        boolean ordered = "ol".equalsIgnoreCase(listElement.tagName());
        int start = positiveInt(listElement.attr("start"));
        ListBlock list = new ListBlock(ordered, start);
        for (Element child : listElement.children()) {
            if (!"li".equalsIgnoreCase(child.tagName())) continue;
            ListItem item = new ListItem();
            List<Node> ownNodes = new ArrayList<>();
            for (Node node : child.childNodes()) {
                if (node instanceof Element && ("ul".equalsIgnoreCase(((Element) node).tagName())
                    || "ol".equalsIgnoreCase(((Element) node).tagName()))) {
                    item.setChildren(parseList((Element) node));
                } else ownNodes.add(node);
            }
            Element wrapper = new Element("div");
            for (Node node : ownNodes) wrapper.appendChild(node.clone());
            List<DocumentBlock> itemBlocks = parseContainerBlocks(wrapper);
            for (DocumentBlock block : itemBlocks) item.addBlock(block);
            list.addItem(item);
        }
        return list;
    }

    /**
     * 解析 pre/code 元素并提取可选语言。
     */
    private CodeBlock parseCodeBlock(Element pre) {
        Element code = pre.selectFirst("code");
        Element source = code == null ? pre : code;
        String language = null;
        for (String className : source.classNames()) {
            if (className.startsWith("language-")) language = className.substring("language-".length());
        }
        return new CodeBlock(source.wholeText(), language);
    }

    /**
     * 将 HTML 表格转换为保留合并和嵌套内容的结构化表格。
     */
    private TableBlock parseTable(Element table) {
        TableBlock result = new TableBlock();
        Map<Integer, Integer> occupiedThroughRow = new HashMap<>();
        List<Element> rows = directRows(table);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            TableRow row = new TableRow();
            int columnIndex = 0;
            for (Element cell : rows.get(rowIndex).children()) {
                if (!"td".equalsIgnoreCase(cell.tagName()) && !"th".equalsIgnoreCase(cell.tagName())) continue;
                while (occupiedThroughRow.containsKey(columnIndex)
                    && occupiedThroughRow.get(columnIndex) >= rowIndex) columnIndex++;
                int rowSpan = positiveInt(cell.attr("rowspan"));
                int colSpan = positiveInt(cell.attr("colspan"));
                row.addCell(new TableCell(parseContainerBlocks(cell), columnIndex, rowSpan, colSpan,
                    "th".equalsIgnoreCase(cell.tagName())));
                if (rowSpan > 1) for (int column = columnIndex; column < columnIndex + colSpan; column++) {
                    occupiedThroughRow.put(column, rowIndex + rowSpan - 1);
                }
                columnIndex += colSpan;
            }
            if (!row.getCells().isEmpty()) result.addRow(row);
        }
        return result;
    }

    /**
     * 获取当前表格拥有的行，排除嵌套表格行。
     */
    private List<Element> directRows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            if ("tr".equalsIgnoreCase(child.tagName())) rows.add(child);
            else if ("thead".equalsIgnoreCase(child.tagName()) || "tbody".equalsIgnoreCase(child.tagName())
                || "tfoot".equalsIgnoreCase(child.tagName())) {
                for (Element row : child.children()) if ("tr".equalsIgnoreCase(row.tagName())) rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 递归解析行内节点及继承样式。
     */
    private List<InlineContent> parseInlineNodes(List<Node> nodes, InlineStyle inherited) {
        List<InlineContent> content = new ArrayList<>();
        for (Node node : nodes) {
            if (node instanceof TextNode) {
                String text = ((TextNode) node).getWholeText();
                if (!text.isEmpty()) content.add(inherited.styled(text));
                continue;
            }
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if ("br".equals(tag)) content.add(new LineBreakInline());
            else if ("img".equals(tag)) content.add(new ImageInline(
                element.attr("src"), element.attr("alt"), element.attr("title")));
            else if ("a".equals(tag)) content.add(new LinkInline(
                parseInlineNodes(element.childNodes(), inherited), element.attr("href"), element.attr("title")));
            else {
                InlineStyle style = inherited.with(tag);
                content.addAll(parseInlineNodes(element.childNodes(), style));
            }
        }
        return content;
    }

    /**
     * 判断行内列表是否包含可见文本、换行或图片。
     */
    private boolean hasVisibleInline(List<InlineContent> content) {
        for (InlineContent inline : content) {
            if (inline instanceof ImageInline || inline instanceof LineBreakInline) return true;
            if (!inline.getPlainText().trim().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 将属性解析为正整数。
     */
    private int positiveInt(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (Exception ignored) {
            return 1;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 12;
    }

    /**
     * HTML 行内样式继承状态。
     */
    private static final class InlineStyle {
        /**
         * 无样式初始状态。
         */
        private static final InlineStyle NONE = new InlineStyle(false, false, false, false);
        /**
         * 是否加粗。
         */
        private final boolean bold;
        /**
         * 是否斜体。
         */
        private final boolean italic;
        /**
         * 是否删除线。
         */
        private final boolean strike;
        /**
         * 是否行内代码。
         */
        private final boolean code;

        /**
         * 创建不可变样式状态。
         */
        private InlineStyle(boolean bold, boolean italic, boolean strike, boolean code) {
            this.bold = bold;
            this.italic = italic;
            this.strike = strike;
            this.code = code;
        }

        /**
         * 根据 HTML 标签返回继承后的样式。
         */
        private InlineStyle with(String tag) {
            return new InlineStyle(bold || "strong".equals(tag) || "b".equals(tag),
                italic || "em".equals(tag) || "i".equals(tag),
                strike || "del".equals(tag) || "s".equals(tag) || "strike".equals(tag),
                code || "code".equals(tag));
        }

        /**
         * 根据当前状态创建普通或带样式文本。
         */
        private InlineContent styled(String text) {
            return bold || italic || strike || code
                ? new StyledTextInline(text, bold, italic, strike, code) : new TextInline(text);
        }
    }
}
