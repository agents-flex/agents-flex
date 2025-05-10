/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.prompt.template;

import com.agentsflex.core.prompt.TextPrompt;
import com.agentsflex.core.util.MapUtil;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextPromptTemplate implements PromptTemplate<TextPrompt> {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");
    private static final Map<String, TextPromptTemplate> CACHES = new ConcurrentHashMap<>();

    private final String originalTemplate;
    private final List<TemplateToken> tokens;

    /**
     * 构造函数：初始化时解析模板内容
     *
     * @param template 模板字符串
     */
    public TextPromptTemplate(String template) {
        this.originalTemplate = template != null ? template : "";
        this.tokens = parseTemplate(this.originalTemplate);
    }

    /**
     * 创建 TextPromptTemplate 实例
     */
    public static TextPromptTemplate of(String template) {
        String finalTemplate = template != null ? template : "";
        return MapUtil.computeIfAbsent(CACHES, finalTemplate, k -> new TextPromptTemplate(finalTemplate));
    }

    /**
     * 格式化模板
     */
    public TextPrompt format(Map<String, Object> rootMap) {
        return new TextPrompt(formatToString(rootMap));
    }

    /**
     * 格式化模板
     */
    public String formatToString(Map<String, Object> rootMap) {
        if (tokens.isEmpty()) return "";

        if (rootMap == null) {
            rootMap = Collections.emptyMap();
        }

        StringBuilder sb = new StringBuilder(originalTemplate.length() + 256);

        for (TemplateToken token : tokens) {
            if (token.isStatic()) {
                sb.append(token.content);
            } else {
                Object value = getValueByJsonPath(rootMap, token.expression);
                String replacement = value != null ? value.toString() : token.defaultValue;
                sb.append(replacement);
            }
        }

        return sb.toString();
    }

    /**
     * 解析模板为 Token 列表
     */
    private List<TemplateToken> parseTemplate(String template) {
        List<TemplateToken> result = new ArrayList<>();

        if (template == null || template.isEmpty()) return result;

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        int lastEnd = 0;

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // 添加前面的静态文本
            if (start > lastEnd) {
                result.add(TemplateToken.staticText(template.substring(lastEnd, start)));
            }

            // 解析占位符
            String content = matcher.group(1);
            ParseResult parseResult = parseExpressionWithDefault(content);
            result.add(TemplateToken.dynamic(parseResult.expression, parseResult.defaultValue));

            lastEnd = end;
        }

        // 添加结尾的静态文本
        if (lastEnd < template.length()) {
            result.add(TemplateToken.staticText(template.substring(lastEnd)));
        }

        return result;
    }

    /**
     * 解析带默认值的表达式，如 "user.name ?? '匿名'"
     */
    private ParseResult parseExpressionWithDefault(String content) {
        String[] parts = content.split("\\s*\\?\\?\\s*", 2);
        if (parts.length == 2) {
            String expr = parts[0].trim();
            String defaultValue = unquote(parts[1].trim());
            return new ParseResult(expr, defaultValue);
        } else {
            return new ParseResult(content.trim(), null);
        }
    }

    /**
     * 去掉字符串两边的引号（单引号或双引号）
     */
    private String unquote(String str) {
        if ((str.startsWith("'") && str.endsWith("'")) ||
            (str.startsWith("\"") && str.endsWith("\""))) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }

    /**
     * 使用 FastJSON JSONPath 获取嵌套值
     */
    private Object getValueByJsonPath(Map<String, Object> root, String path) {
        try {
            return JSONPath.eval(root, "$." + path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Token 类型定义
     */
    private static class TemplateToken {
        boolean isStatic;
        String content;
        String expression;
        String defaultValue;

        private TemplateToken(boolean isStatic, String content, String expression, String defaultValue) {
            this.isStatic = isStatic;
            this.content = content;
            this.expression = expression;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
        }

        static TemplateToken staticText(String text) {
            return new TemplateToken(true, text, null, null);
        }

        static TemplateToken dynamic(String expr, String defaultValue) {
            return new TemplateToken(false, null, expr, defaultValue);
        }

        boolean isStatic() {
            return isStatic;
        }
    }

    /**
     * 内部类：用于保存解析后的表达式和默认值
     */
    private static class ParseResult {
        String expression;
        String defaultValue;

        ParseResult(String expression, String defaultValue) {
            this.expression = expression;
            this.defaultValue = defaultValue;
        }
    }

}
