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
package com.agentsflex.core.document;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 与内嵌 HTML 文档图片描述生成器。
 *
 * <p>该工具扫描文档正文中的 Markdown 图片语法和 HTML {@code <img>} 标签，并将图片地址作为
 * 多模态消息发送给支持视觉输入的 {@link ChatModel}。模型返回的描述会作为独立正文段落插入到
 * 对应图片所在行之后，不会改写 Markdown 替代文本或 HTML {@code alt} 属性。例如：</p>
 *
 * <pre>{@code
 * ![](https://example.com/chart.png)
 *
 * <!-- image-description:start -->
 * 一张展示季度增长趋势的折线图。
 * <!-- image-description:end -->
 * }</pre>
 *
 * <p>每张待描述图片会触发一次同步模型调用。一行存在多张图片时，将按照图片出现顺序逐张调用。
 * 图片后方已经存在描述标记时，认为该图片已有描述并跳过；代码围栏中的图片语法和标签也不会处理。</p>
 *
 * <p>图片地址会原样写入 {@code UserMessage.imageUrls}。HTTP URL 和
 * {@code data:image/...;base64,...} Data URI 均可被识别，但能否实际读取取决于所使用的
 * ChatModel 适配器及具体模型的多模态能力。没有 Data URI 前缀的裸 Base64 字符串不能保证可用。</p>
 *
 * <p>该类不负责并发、重试和限流。模型错误响应会继续抛出对应模型异常，调用方应根据批量导入策略
 * 决定重试、跳过或终止任务。</p>
 */
public class DocumentImageDescriber {

    /**
     * 默认图片描述提示词。
     *
     * <p>{@code {alt}} 是图片替代文本占位符，调用模型前会替换为 Markdown 图片替代文本或 HTML
     * {@code alt} 属性。</p>
     */
    public static final String DEFAULT_PROMPT_TEMPLATE =
        "请准确、简洁地描述这张图片。描述应适合写入文档并帮助后续检索。"
            + "只输出图片描述正文，不要添加‘图片描述’等前缀，不要使用 Markdown。"
            + "图片原始替代文本：{alt}";

    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
        "!\\[((?:\\\\.|[^\\]])*)\\]\\(\\s*(<[^>\\r\\n]+>|[^\\s)]+)"
            + "(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile("(?i)<img\\b[^>]*>");
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile(
        "(?i)(?:^|\\s)(src|alt)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final String DESCRIPTION_START = "<!-- image-description:start -->";
    private static final String DESCRIPTION_END = "<!-- image-description:end -->";

    private final ChatModel chatModel;
    private ChatOptions chatOptions = ChatOptions.builder().temperature(0.2f).build();
    private String promptTemplate = DEFAULT_PROMPT_TEMPLATE;

    /**
     * 创建图片描述生成器。
     *
     * @param chatModel 支持图片输入的聊天模型；不能为 {@code null}
     * @throws IllegalArgumentException 当 {@code chatModel} 为 {@code null} 时抛出
     */
    public DocumentImageDescriber(ChatModel chatModel) {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel must not be null");
        }
        this.chatModel = chatModel;
    }

    /**
     * 为文档正文中的 Markdown 和 HTML 图片生成描述。
     *
     * <p>该方法会直接修改传入对象的 {@link Document#getContent() content}，并返回同一个
     * Document 实例。文档的 ID、标题、向量、分数和 Metadata 均不会修改。</p>
     *
     * <p>正文为 {@code null}、空字符串或纯空白时保持不变，也不会调用模型。</p>
     *
     * @param document 待增强的文档；不能为 {@code null}
     * @return 已更新正文的原 Document 实例
     * @throws IllegalArgumentException 当 {@code document} 为 {@code null} 时抛出
     * @throws RuntimeException         当底层 ChatModel 调用失败或返回错误响应时传播模型异常
     */
    public Document describe(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        document.setContent(describe(document.getContent()));
        return document;
    }

    /**
     * 为 Markdown 字符串中的图片生成描述并返回增强后的新字符串。
     *
     * <p>支持 Markdown 图片以及 HTML {@code <img src="..." alt="...">} 标签中的图片。Markdown
     * 图片支持普通 URL、尖括号包裹的 URL 和单行 Data URI。图片标题和 HTML 其他属性会原样保留但
     * 不会发送给模型，图片替代文本会通过提示词模板中的 {@code {alt}} 传给模型。相对图片地址虽然能够解析，
     * 但调用远程模型前通常需要转换为模型可访问的绝对 URL 或 Data URI。</p>
     *
     * <p>模型返回的描述会在图片下方以普通 Markdown 段落写入。工具会在段落前添加不可见的 HTML
     * 注释标记，以便重复执行时跳过已有描述。该标记不会影响 Markdown/HTML 渲染。如果模型没有
     * 返回消息或描述为空，则保留原图片且不追加内容。输出会沿用输入文本原有的 LF、CRLF 或 CR
     * 换行风格。</p>
     *
     * @param markdown 待处理的 Markdown；可以为 {@code null}
     * @return 插入图片描述后的 Markdown；输入无内容时原样返回
     * @throws RuntimeException 当底层 ChatModel 调用失败或返回错误响应时传播模型异常
     */
    public String describe(String markdown) {
        if (StringUtil.noText(markdown)) {
            return markdown;
        }

        List<Line> lines = splitLines(markdown);
        String defaultLineSeparator = findLineSeparator(lines);
        StringBuilder result = new StringBuilder(markdown.length());
        Fence fence = null;

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Fence lineFence = Fence.parse(line.content);
            boolean inCodeFence = fence != null;
            if (lineFence != null) {
                if (fence == null) {
                    fence = lineFence;
                } else if (fence.matches(lineFence)) {
                    fence = null;
                }
            }

            result.append(line.content).append(line.separator);
            if (inCodeFence || lineFence != null || hasFollowingDescription(lines, i)) {
                continue;
            }

            List<String> descriptions = new ArrayList<>();
            for (ImageReference image : findImages(line.content)) {
                String description = requestDescription(image.url, image.alt);
                if (StringUtil.hasText(description)) {
                    descriptions.add(description);
                }
            }
            if (descriptions.isEmpty()) {
                continue;
            }

            String separator = !line.separator.isEmpty() ? line.separator : defaultLineSeparator;
            if (line.separator.isEmpty()) {
                result.append(separator);
            }
            result.append(separator);
            for (int descriptionIndex = 0; descriptionIndex < descriptions.size(); descriptionIndex++) {
                result.append(DESCRIPTION_START).append(separator);
                appendParagraph(result, descriptions.get(descriptionIndex), separator);
                result.append(separator).append(DESCRIPTION_END);
                boolean lastDescription = descriptionIndex == descriptions.size() - 1;
                boolean lastInputLine = i == lines.size() - 1;
                if (!lastDescription || !lastInputLine) {
                    result.append(separator).append(separator);
                } else if (!line.separator.isEmpty()) {
                    result.append(separator);
                }
            }
        }
        return result.toString();
    }

    /**
     * 获取每次图片描述请求使用的模型参数。
     *
     * @return 当前 ChatOptions，默认温度为 {@code 0.2}
     */
    public ChatOptions getChatOptions() {
        return chatOptions;
    }

    /**
     * 设置每次图片描述请求使用的模型参数。
     *
     * <p>可以通过该参数指定视觉模型、温度、最大输出 Token 等。该对象会传给每一次图片模型调用。</p>
     *
     * @param chatOptions 模型调用参数；不能为 {@code null}
     * @throws IllegalArgumentException 当参数为 {@code null} 时抛出
     */
    public void setChatOptions(ChatOptions chatOptions) {
        if (chatOptions == null) {
            throw new IllegalArgumentException("chatOptions must not be null");
        }
        this.chatOptions = chatOptions;
    }

    /**
     * 获取当前图片描述提示词模板。
     *
     * @return 提示词模板
     */
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * 设置图片描述提示词模板。
     *
     * <p>模板可以包含可选的 {@code {alt}} 占位符，调用模型前会替换为图片替代文本。
     * 如果不需要替代文本，可以使用不包含该占位符的模板。</p>
     *
     * @param promptTemplate 新的提示词模板；不能为 {@code null}、空字符串或纯空白
     * @throws IllegalArgumentException 当模板无有效文本时抛出
     */
    public void setPromptTemplate(String promptTemplate) {
        if (StringUtil.noText(promptTemplate)) {
            throw new IllegalArgumentException("promptTemplate must not be blank");
        }
        this.promptTemplate = promptTemplate;
    }

    /**
     * 构建单张图片的多模态请求并提取模型返回文本。
     */
    private String requestDescription(String imageUrl, String alt) {
        SimplePrompt prompt = new SimplePrompt(promptTemplate.replace("{alt}", alt == null ? "" : alt));
        prompt.addImageUrl(imageUrl);
        AiMessageResponse response = chatModel.chat(prompt, chatOptions);
        if (response == null) {
            return null;
        }
        if (response.isError()) {
            response.throwIfError();
        }
        AiMessage message = response.getMessage();
        if (message == null) {
            return null;
        }
        return StringUtil.hasText(message.getContent()) ? message.getContent() : message.getReasoningContent();
    }

    /**
     * 将模型返回的单行或多行描述规范化为一个普通 Markdown 段落。
     */
    private static void appendParagraph(StringBuilder result, String description, String separator) {
        String[] descriptionLines = description.trim().split("\\r\\n|\\n|\\r", -1);
        boolean wroteLine = false;
        for (String descriptionLine : descriptionLines) {
            String normalized = stripQuotePrefix(descriptionLine.trim());
            if (normalized.isEmpty()) {
                continue;
            }
            if (wroteLine) {
                result.append(separator);
            }
            result.append(normalized);
            wroteLine = true;
        }
    }

    /**
     * 图片下方存在描述开始标记时视为已有描述，避免重复执行时反复追加。
     */
    private static boolean hasFollowingDescription(List<Line> lines, int currentIndex) {
        if (currentIndex + 1 >= lines.size()) return false;
        String nextLine = lines.get(currentIndex + 1).content;
        if (isDescriptionStart(nextLine)) return true;
        return nextLine.trim().isEmpty() && currentIndex + 2 < lines.size()
            && isDescriptionStart(lines.get(currentIndex + 2).content);
    }

    private static boolean isDescriptionStart(String line) {
        return DESCRIPTION_START.equals(line.trim());
    }

    /**
     * 拆分文本的同时保留每一行原始换行符，以避免处理后改变文档换行风格。
     */
    private static List<Line> splitLines(String value) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\n' && current != '\r') {
                continue;
            }
            int separatorEnd = i + 1;
            if (current == '\r' && separatorEnd < value.length() && value.charAt(separatorEnd) == '\n') {
                separatorEnd++;
            }
            lines.add(new Line(value.substring(start, i), value.substring(i, separatorEnd)));
            start = separatorEnd;
            i = separatorEnd - 1;
        }
        if (start < value.length()) {
            lines.add(new Line(value.substring(start), ""));
        }
        return lines;
    }

    private static String findLineSeparator(List<Line> lines) {
        for (Line line : lines) {
            if (!line.separator.isEmpty()) {
                return line.separator;
            }
        }
        return "\n";
    }

    private static String stripAngles(String value) {
        return value.length() > 1 && value.charAt(0) == '<' && value.charAt(value.length() - 1) == '>'
            ? value.substring(1, value.length() - 1) : value;
    }

    /**
     * 提取一行中的 Markdown 和 HTML 图片，并按原文中的出现位置排序。
     */
    private static List<ImageReference> findImages(String line) {
        List<ImageReference> images = new ArrayList<>();
        Matcher markdownMatcher = MARKDOWN_IMAGE.matcher(line);
        while (markdownMatcher.find()) {
            images.add(new ImageReference(markdownMatcher.start(), stripAngles(markdownMatcher.group(2)),
                unescapeAlt(markdownMatcher.group(1))));
        }

        Matcher htmlMatcher = HTML_IMAGE.matcher(line);
        while (htmlMatcher.find()) {
            String src = null;
            String alt = "";
            Matcher attributeMatcher = HTML_ATTRIBUTE.matcher(htmlMatcher.group());
            while (attributeMatcher.find()) {
                String value = firstAttributeValue(attributeMatcher);
                if ("src".equalsIgnoreCase(attributeMatcher.group(1))) {
                    src = value;
                } else {
                    alt = value;
                }
            }
            if (StringUtil.hasText(src)) {
                images.add(new ImageReference(htmlMatcher.start(), src, alt));
            }
        }
        images.sort(Comparator.comparingInt(imageReference -> imageReference.position));
        return images;
    }

    private static String firstAttributeValue(Matcher matcher) {
        for (int group = 2; group <= 4; group++) {
            if (matcher.group(group) != null) return matcher.group(group);
        }
        return "";
    }

    private static String unescapeAlt(String value) {
        return value == null ? "" : value.replace("\\]", "]").replace("\\[", "[");
    }

    private static String stripQuotePrefix(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '>') {
            index++;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
        }
        return value.substring(index);
    }

    private static final class Line {
        private final String content;
        private final String separator;

        private Line(String content, String separator) {
            this.content = content;
            this.separator = separator;
        }
    }

    private static final class ImageReference {
        private final int position;
        private final String url;
        private final String alt;

        private ImageReference(int position, String url, String alt) {
            this.position = position;
            this.url = url;
            this.alt = alt;
        }
    }

    /**
     * 表示 Markdown 代码围栏的标记字符和长度，用于跳过代码示例中的图片语法。
     */
    private static final class Fence {
        private final char marker;
        private final int length;

        private Fence(char marker, int length) {
            this.marker = marker;
            this.length = length;
        }

        private static Fence parse(String line) {
            String trimmed = line.trim();
            if (trimmed.length() < 3 || (trimmed.charAt(0) != '`' && trimmed.charAt(0) != '~')) {
                return null;
            }
            char marker = trimmed.charAt(0);
            int length = 1;
            while (length < trimmed.length() && trimmed.charAt(length) == marker) {
                length++;
            }
            return length >= 3 ? new Fence(marker, length) : null;
        }

        private boolean matches(Fence other) {
            return marker == other.marker && other.length >= length;
        }
    }
}
