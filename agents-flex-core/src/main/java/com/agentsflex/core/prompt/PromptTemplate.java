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
package com.agentsflex.core.prompt;

import com.agentsflex.core.util.MapUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONWriter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本模板引擎，用于将包含 {{xxx}} 占位符的字符串模板，动态渲染为最终文本。
 * 支持 JSONPath 取值语法与 “??” 空值兜底逻辑。
 * <p>
 * 例如：
 * 模板: "Hello {{ user.name ?? 'Unknown' }}!"
 * 数据: { "user": { "name": "Alice" } }
 * 输出: "Hello Alice!"
 * <p>
 * 支持缓存模板与 JSONPath 编译结果，提升性能。
 */
public class PromptTemplate {

    /**
     * 匹配 {{ expression }} 的正则表达式
     */
    private static final Pattern PLACEHOLDER_PATTERN =
        Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    /**
     * 模板缓存（按原始模板字符串）
     */
    private static final Map<String, PromptTemplate> TEMPLATE_CACHE = new ConcurrentHashMap<>();

    /**
     * JSONPath 编译缓存，避免重复编译
     */
    private static final Map<String, JSONPath> JSONPATH_CACHE = new ConcurrentHashMap<>();

    /**
     * 原始模板字符串
     */
    private final String originalTemplate;

    /**
     * 模板中拆分出的静态与动态 token 列表
     */
    private final List<TemplateToken> tokens;

    public PromptTemplate(String template) {
        this.originalTemplate = template != null ? template : "";
        this.tokens = Collections.unmodifiableList(parseTemplate(this.originalTemplate));
    }

    /**
     * 从缓存中获取或新建模板实例
     */
    public static PromptTemplate of(String template) {
        String finalTemplate = template != null ? template : "";
        return MapUtil.computeIfAbsent(TEMPLATE_CACHE, finalTemplate, k -> new PromptTemplate(finalTemplate));
    }

    /**
     * 清空模板与 JSONPath 缓存
     */
    public static void clearCache() {
        TEMPLATE_CACHE.clear();
        JSONPATH_CACHE.clear();
    }


    /**
     * 将模板格式化为字符串
     */
    public String format(Map<String, Object> rootMap) {
        return format(rootMap, false);
    }

    /**
     * 将模板格式化为字符串，可选择是否对结果进行 JSON 转义
     *
     * @param rootMap             数据上下文
     * @param escapeForJsonOutput 是否对结果进行 JSON 字符串转义
     */
    public String format(Map<String, Object> rootMap, boolean escapeForJsonOutput) {
        if (tokens.isEmpty()) return originalTemplate;
        if (rootMap == null) rootMap = Collections.emptyMap();

        StringBuilder sb = new StringBuilder(originalTemplate.length() + 64);

        for (TemplateToken token : tokens) {
            if (token.isStatic) {
                // 静态文本，直接拼接
                sb.append(token.content);
                continue;
            }

            // 动态表达式求值
            String value = evaluate(token.parseResult, rootMap, escapeForJsonOutput);

            // 没有兜底且值为空时抛出异常
            if (!token.explicitEmptyFallback && value.isEmpty()) {
                throw new IllegalArgumentException(String.format(
                    "Missing value for expression: \"%s\"%nTemplate: %s%nProvided parameters:%n%s",
                    token.rawExpression,
                    originalTemplate,
                    JSON.toJSONString(rootMap, JSONWriter.Feature.PrettyFormat)
                ));
            }
            sb.append(value);
        }

        return sb.toString();
    }

    /**
     * 解析模板字符串，将其拆解为静态文本与动态占位符片段
     */
    private List<TemplateToken> parseTemplate(String template) {
        List<TemplateToken> result = new ArrayList<>(template.length() / 8);
        if (template == null || template.isEmpty()) return result;

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        int lastEnd = 0;

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            // 处理 {{ 前的静态文本
            if (start > lastEnd) {
                result.add(TemplateToken.staticText(template.substring(lastEnd, start)));
            }

            // 处理 {{ ... }} 动态部分
            String rawExpr = matcher.group(1);
            TemplateParseResult parsed = parseTemplateExpression(rawExpr);
            result.add(TemplateToken.dynamic(parsed.parseResult, rawExpr, parsed.explicitEmptyFallback));

            lastEnd = end;
        }

        // 末尾剩余静态文本
        if (lastEnd < template.length()) {
            result.add(TemplateToken.staticText(template.substring(lastEnd)));
        }

        return result;
    }

    /**
     * 解析单个表达式内容，处理 ?? 空值兜底逻辑。
     * 例如： user.name ?? user.nick ?? "未知"
     */
    private TemplateParseResult parseTemplateExpression(String expr) {
        // 无 ?? 表示该值必填
        if (!expr.contains("??")) {
            return new TemplateParseResult(new ParseResult(expr.trim(), null), false);
        }

        // 按 ?? 分割，支持链式兜底
        String[] parts = expr.split("\\s*\\?\\?\\s*", -1);
        boolean explicitEmptyFallback = parts[parts.length - 1].trim().isEmpty();

        // 从右往左构建兜底链
        ParseResult result = null;
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            if (p.isEmpty()) p = "\"\""; // 空串转为 "" 字面量
            result = new ParseResult(p, result);
        }

        return new TemplateParseResult(result, explicitEmptyFallback);
    }

    /**
     * 递归求值表达式（支持多级兜底）
     */
    private String evaluate(ParseResult pr, Map<String, Object> root, boolean escapeForJsonOutput) {
        if (pr == null) return "";

        // 字面量直接返回
        if (pr.isLiteral) {
            String literal = pr.getUnquotedLiteral();
            return escapeForJsonOutput ? escapeJsonString(literal) : literal;
        }

        // 尝试从 JSONPath 取值
        Object value = getValueByJsonPath(root, pr.expression, escapeForJsonOutput);
        if (value != null) {
            return value.toString();
        }

        // 若未取到，则尝试 fallback
        return evaluate(pr.defaultResult, root, escapeForJsonOutput);
    }

    /**
     * 根据 JSONPath 获取对象值
     */
    private Object getValueByJsonPath(Map<String, Object> root, String path, boolean escapeForJsonOutput) {
        try {
            String fullPath = path.startsWith("$") ? path : "$." + path;
            JSONPath compiled = MapUtil.computeIfAbsent(JSONPATH_CACHE, fullPath, JSONPath::compile);
            Object value = compiled.eval(root);
            if (escapeForJsonOutput && value instanceof String) {
                return escapeJsonString((String) value);
            }
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 将字符串进行 JSON 安全转义
     */
    private static String escapeJsonString(String input) {
        if (input == null || input.isEmpty()) return input;
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * 去掉字符串两端的引号
     */
    private static String unquote(String str) {
        if (str == null || str.length() < 2) return str;
        char first = str.charAt(0);
        char last = str.charAt(str.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }


    /**
     * 模板片段对象。
     * 每个模板字符串会被解析为若干个 TemplateToken：
     * - 静态文本（isStatic = true）
     * - 动态表达式（isStatic = false）
     */
    private static class TemplateToken {
        final boolean isStatic;              // 是否为静态文本
        final String content;                // 静态文本内容
        final ParseResult parseResult;       // 动态解析结果（表达式树）
        final String rawExpression;          // 原始表达式字符串
        final boolean explicitEmptyFallback; // 是否显式声明空兜底（以 ?? 结尾）

        private TemplateToken(boolean isStatic, String content,
                              ParseResult parseResult, String rawExpression,
                              boolean explicitEmptyFallback) {
            this.isStatic = isStatic;
            this.content = content;
            this.parseResult = parseResult;
            this.rawExpression = rawExpression;
            this.explicitEmptyFallback = explicitEmptyFallback;
        }

        /**
         * 创建静态文本 token
         */
        static TemplateToken staticText(String text) {
            return new TemplateToken(true, text, null, null, false);
        }

        /**
         * 创建动态表达式 token
         */
        static TemplateToken dynamic(ParseResult parseResult, String rawExpression, boolean explicitEmptyFallback) {
            return new TemplateToken(false, null, parseResult, rawExpression, explicitEmptyFallback);
        }
    }

    /**
     * 表达式解析结果。
     * 支持嵌套的默认值链，如：user.name ?? user.nick ?? "匿名"
     */
    private static class ParseResult {
        final String expression;         // 当前表达式内容（可能是 JSONPath 或字符串字面量）
        final ParseResult defaultResult; // 默认值链的下一个节点
        final boolean isLiteral;         // 是否为字面量字符串（'xxx' 或 "xxx"）

        ParseResult(String expression, ParseResult defaultResult) {
            this.expression = expression;
            this.defaultResult = defaultResult;
            this.isLiteral = isLiteralExpression(expression);
        }

        /**
         * 判断是否是字符串字面量
         */
        private static boolean isLiteralExpression(String expr) {
            if (expr == null || expr.length() < 2) return false;
            char first = expr.charAt(0);
            char last = expr.charAt(expr.length() - 1);
            return (first == '\'' && last == '\'') || (first == '"' && last == '"');
        }

        /**
         * 返回去除引号后的字符串字面量值
         */
        String getUnquotedLiteral() {
            if (!isLiteral) throw new IllegalStateException("Not a literal: " + expression);
            return unquote(expression);
        }
    }

    /**
     * 模板解析的最终结果，包含：
     * - 解析后的表达式树（ParseResult）
     * - 是否显式声明空兜底
     */
    private static class TemplateParseResult {
        final ParseResult parseResult;
        final boolean explicitEmptyFallback;

        TemplateParseResult(ParseResult parseResult, boolean explicitEmptyFallback) {
            this.parseResult = parseResult;
            this.explicitEmptyFallback = explicitEmptyFallback;
        }
    }
}
