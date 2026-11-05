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
package com.agentsflex.skill.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 解析带有可选 YAML front matter 的 Markdown 文档。
 *
 * <p>front matter 必须位于文件开头，并由两行 {@code ---} 包围。本解析器提取其中的
 * 简单键值对作为元数据，同时保留分隔符之后的 Markdown 正文。</p>
 *
 * <p>示例：</p>
 *
 * <pre>{@code
 * ---
 * name: pdf
 * description: Create PDF documents
 * ---
 *
 * # Heading
 *
 * Skill instructions here.
 * }</pre>
 *
 * <p>支持的语法：</p>
 * <ul>
 * <li>使用第一个冒号分隔键和值的单行键值对；</li>
 * <li>无引号、单引号或双引号包裹的值；</li>
 * <li>不含 front matter 的普通 Markdown；</li>
 * <li>{@code null} 或空输入。</li>
 * </ul>
 *
 * <p>分隔符按整行识别：只有内容为 {@code ---} 的行才算分隔符，值里出现的 {@code ---}
 * （例如描述中用破折号写法）不会提前结束 front matter。文件开头允许存在 UTF-8 BOM。</p>
 *
 * <p>这不是完整 YAML 解析器，不支持嵌套对象、数组、块文本等高级 YAML 语法。</p>
 *
 * @author Christian Tzolov
 */
public class MarkdownParser {

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * 已解析的 front matter 键值对。
     */
    private Map<String, Object> frontMatter;

    /**
     * front matter 结束后的 Markdown 正文。
     */
    private String content;

    /**
     * 创建解析器并立即解析输入内容。
     *
     * @param markdown 待解析 Markdown；可以为 {@code null} 或空字符串
     */
    public MarkdownParser(String markdown) {

        frontMatter = new HashMap<>();
        content = "";

        if (markdown == null || markdown.isEmpty()) {
            return;
        }

        int documentStart = markdown.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
        int afterOpeningLine = lineAfter(markdown, documentStart);

        // 只有文件开头的 --- 独立成行时才会被识别为 front matter 起始分隔符。
        if (afterOpeningLine < 0 || !isDelimiterLine(markdown, documentStart, afterOpeningLine)) {
            content = markdown.substring(documentStart);
            return;
        }

        // 逐行查找结束分隔符：值内部的 --- 不会提前结束 front matter。
        int position = afterOpeningLine;
        while (position < markdown.length()) {
            int next = lineAfter(markdown, position);
            int lineEnd = next < 0 ? markdown.length() : next;
            if (isDelimiterLine(markdown, position, lineEnd)) {
                parseFrontMatter(markdown.substring(afterOpeningLine, position).trim());
                // 跳过结束分隔符，并去掉正文首尾空白。
                content = markdown.substring(lineEnd).trim();
                return;
            }
            if (next < 0) {
                break;
            }
            position = next;
        }

        // 缺少结束分隔符时不猜测元数据，整份文档按正文处理。
        content = markdown.substring(documentStart);

    }

    private void parseFrontMatter(String frontMatterSection) {
        String[] lines = frontMatterSection.split("\n");

        for (String line : lines) {
            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            // 只按第一个冒号切分，值本身可以继续包含冒号。
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                // 去掉成对的单引号或双引号。
                value = removeQuotes(value);

                frontMatter.put(key, value);
            }
        }
    }

    /**
     * 返回一行结束符之后的位置；该行没有换行符时返回 {@code -1}。
     */
    private static int lineAfter(String markdown, int start) {
        for (int i = start; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (c == '\r') {
                return i + 1 < markdown.length() && markdown.charAt(i + 1) == '\n' ? i + 2 : i + 1;
            }
        }
        return -1;
    }

    /**
     * 判断 {@code [start, end)} 这一行去掉首尾空白后是否恰好是 {@code ---}。
     */
    private static boolean isDelimiterLine(String markdown, int start, int end) {
        int from = start;
        int to = end;
        while (from < to && Character.isWhitespace(markdown.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(markdown.charAt(to - 1))) {
            to--;
        }
        return to - from == 3
            && markdown.charAt(from) == '-'
            && markdown.charAt(from + 1) == '-'
            && markdown.charAt(from + 2) == '-';
    }

    private String removeQuotes(String value) {
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 获取 front matter 的副本，调用方修改返回 Map 不会影响解析器内部状态。
     *
     * @return front matter 键值对；未提供元数据时为空 Map
     */
    public Map<String, Object> getFrontMatter() {
        return new HashMap<>(frontMatter);
    }

    /**
     * 获取去掉 front matter 后的 Markdown 正文。
     *
     * @return 正文；输入为空时返回空字符串
     */
    public String getContent() {
        return content;
    }
}
