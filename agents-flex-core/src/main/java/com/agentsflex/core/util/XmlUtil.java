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
package com.agentsflex.core.util;

/**
 * 生成 XML 文本时使用的转义工具。
 *
 * <p>把用户或第三方提供的内容拼接进 XML 之前必须转义：{@code &amp;}、{@code &lt;}
 * 这类字符在 XML 中有语法含义，原样写入会让整段文本无法解析，或者让内容提前闭合
 * 标签、变成新的兄弟节点。</p>
 */
public final class XmlUtil {

    private XmlUtil() {
    }

    /**
     * 转义 XML 文本内容中的特殊字符。
     *
     * <p>该方法只负责文本内容，不适用于标签名：XML 标签名不允许出现 {@code &}、
     * {@code <} 等字符，转义后的形状依然不是合法标签名，这类输入应在拼接前校验。</p>
     *
     * @param value 待转义的内容，{@code null} 视为空字符串
     * @return 转义后的文本
     */
    public static String escapeXml(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        int length = text.length();
        StringBuilder escaped = null;
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            String replacement;
            switch (ch) {
                case '&':
                    replacement = "&amp;";
                    break;
                case '<':
                    replacement = "&lt;";
                    break;
                case '>':
                    replacement = "&gt;";
                    break;
                case '"':
                    replacement = "&quot;";
                    break;
                case '\'':
                    replacement = "&apos;";
                    break;
                default:
                    replacement = null;
                    break;
            }
            if (replacement == null) {
                if (escaped != null) {
                    escaped.append(ch);
                }
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(length + 16);
                escaped.append(text, 0, i);
            }
            escaped.append(replacement);
        }
        return escaped == null ? text : escaped.toString();
    }
}
