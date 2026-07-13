/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.util;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型工具参数 JSON 容错处理器。
 *
 * <p>大模型有时会在 JSON 值中直接输出 JavaScript 表达式，例如
 * {@code function (...) {...}}、{@code new Date()}、正则字面量或箭头函数。
 * 这些内容不是标准 JSON，fastjson2 无法直接解析。本类通过字符级扫描识别这些表达式，
 * 将表达式的完整源码转换为 JSON 字符串，同时尽量保持其他合法 JSON 内容不变。</p>
 *
 * <p>这里不执行任何 JavaScript，也不会把表达式转换成 Java 对象。例如
 * {@code {"value": new Date()}} 会被处理为
 * {@code {"value": "new Date()"}}。</p>
 *
 * <p>使用字符级扫描而不是简单的正则替换，是因为函数中可能包含嵌套函数、对象、数组、
 * 字符串、注释、模板字符串、除法和正则表达式。简单正则无法可靠判断逗号或花括号是否属于
 * 当前 JSON 层级。</p>
 */
public final class JsonSanitizer {

    private JsonSanitizer() {
    }

    /**
     * 将文本中的常见 JavaScript 值转换为合法的 JSON 字符串值。
     *
     * <p>当前支持函数、异步函数、箭头函数、{@code new} 表达式、正则字面量、
     * {@code undefined}、{@code NaN}、{@code Infinity} 以及单引号字符串。</p>
     *
     * @param text 待处理的 JSON 或类 JSON 文本
     * @return 可供 JSON 解析器继续尝试解析的文本；参数为 {@code null} 或空字符串时原样返回
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder(text.length());
        // 只有位于冒号、数组起始符或逗号之后，才可能开始一个 JSON 值。
        // 该状态可以避免把字段名或普通字符串中的 function/new 等文本错误转换。
        boolean expectingValue = true;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                // 标准 JSON 双引号字符串直接复制，跳过内部的转义字符和结构符号。
                int end = skipQuoted(text, i, '"');
                result.append(text, i, end);
                i = end;
                expectingValue = false;
                continue;
            }
            if (c == '\'') {
                // JSON 不支持单引号字符串，将其内容转义为标准双引号 JSON 字符串。
                int end = skipQuoted(text, i, '\'');
                result.append(JSON.toJSONString(unquote(text.substring(i, end))));
                i = end;
                expectingValue = false;
                continue;
            }
            if (expectingValue && startsJavaScriptValue(text, i)) {
                // 保留整个 JavaScript 表达式的源码，再将源码作为普通 JSON 字符串写入。
                int end = findValueEnd(text, i);
                String value = text.substring(i, end).trim();
                result.append(JSON.toJSONString(value));
                i = end;
                expectingValue = false;
                continue;
            }

            result.append(c);
            if (c == ':' || c == '[' || c == ',') {
                expectingValue = true;
            } else if (!Character.isWhitespace(c) && c != '{') {
                expectingValue = false;
            }
            i++;
        }
        return result.toString();
    }

    /**
     * 从带有说明文字的模型输出中提取所有花括号配对完整的对象候选。
     *
     * <p>例如 {@code 参数如下：{"name":"test"} 请执行} 会提取出
     * {@code {"name":"test"}}。扫描过程中会忽略字符串、模板字符串、注释和正则字面量
     * 内部的花括号，因此正则量词 {@code /a{1,3}/} 不会破坏对象边界判断。</p>
     *
     * <p>文本中可能存在多个对象，方法会按出现顺序全部返回，由调用方逐个尝试解析。</p>
     *
     * @param text 可能包含前缀、后缀或多个对象的文本
     * @return 按出现顺序排列的完整对象候选；没有候选时返回空列表
     */
    public static List<String> extractObjects(String text) {
        List<String> objects = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return objects;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(text, i, c) - 1;
                continue;
            }
            if (c != '{') {
                continue;
            }

            int end = findObjectEnd(text, i);
            if (end > i) {
                objects.add(text.substring(i, end));
                i = end - 1;
            }
        }
        return objects;
    }

    /**
     * 补全缺失的对象花括号。
     *
     * <p>文本不是以 {@code "{"} 开头时补充左花括号，然后根据实际未闭合层级补充右花括号。
     * 不能只通过 {@code endsWith("}")} 判断是否完整，因为末尾花括号可能属于函数体或嵌套对象。</p>
     *
     * @param text 缺少外层花括号或右花括号的类 JSON 文本
     * @return 补全后的对象候选；参数为 {@code null} 或空字符串时原样返回
     */
    public static String completeObject(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String candidate = text.startsWith("{") ? text : "{" + text;
        int depth = objectDepth(candidate);
        if (depth <= 0) {
            return candidate;
        }

        StringBuilder completed = new StringBuilder(candidate.length() + depth).append(candidate);
        while (depth-- > 0) completed.append('}');
        return completed.toString();
    }

    /**
     * 计算文本扫描结束后尚未闭合的对象层数。
     * 字符串、注释和正则内部的花括号不参与计数。
     */
    private static int objectDepth(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(text, i, c) - 1;
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/' || next == '*') {
                    i = skipComment(text, i, next) - 1;
                    continue;
                }
                if (isRegexStart(text, 0, i)) {
                    i = skipRegex(text, i) - 1;
                    continue;
                }
            }
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }

    /**
     * 查找从指定左花括号开始、与其配对的右花括号后一位下标。
     * 返回后一位下标便于直接调用 {@link String#substring(int, int)}；未闭合时返回 -1。
     */
    private static int findObjectEnd(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(text, i, c) - 1;
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/' || next == '*') {
                    i = skipComment(text, i, next) - 1;
                    continue;
                }
                if (isRegexStart(text, start, i)) {
                    i = skipRegex(text, i) - 1;
                    continue;
                }
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        return -1;
    }

    /**
     * 判断值位置是否以已知的 JavaScript 非 JSON 值开始。
     */
    private static boolean startsJavaScriptValue(String text, int start) {
        if (matchesWord(text, start, "function") || matchesWord(text, start, "async")
            || matchesWord(text, start, "new") || matchesWord(text, start, "undefined")
            || matchesWord(text, start, "NaN") || matchesWord(text, start, "Infinity")) {
            return true;
        }
        char c = text.charAt(start);
        if (c == '/') {
            return start + 1 < text.length() && text.charAt(start + 1) != '/' && text.charAt(start + 1) != '*';
        }
        return (c == '(' || isIdentifierStart(c)) && containsArrowBeforeDelimiter(text, start);
    }

    /**
     * 在当前值边界内检查是否存在箭头操作符，用于识别 {@code v => ...} 和
     * {@code (v) => ...}，避免把普通标识符误认为 JavaScript 表达式。
     */
    private static boolean containsArrowBeforeDelimiter(String text, int start) {
        int end = findValueEnd(text, start);
        return text.substring(start, end).contains("=>");
    }

    /**
     * 查找一个 JavaScript 值的结束位置。
     *
     * <p>只有在函数体、数组和圆括号都回到起始层级时，逗号、右花括号或右方括号才是
     * 当前 JSON 值的分隔符。返回值指向分隔符本身，调用方不会吞掉该分隔符。</p>
     */
    private static int findValueEnd(String text, int start) {
        int braces = 0;
        int brackets = 0;
        int parentheses = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(text, i, c) - 1;
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/' || next == '*') {
                    i = skipComment(text, i, next) - 1;
                    continue;
                }
                if (isRegexStart(text, start, i)) {
                    i = skipRegex(text, i) - 1;
                    continue;
                }
            }
            if (c == '{') braces++;
            else if (c == '[') brackets++;
            else if (c == '(') parentheses++;
            else if (c == '}' && braces > 0) braces--;
            else if (c == ']' && brackets > 0) brackets--;
            else if (c == ')' && parentheses > 0) parentheses--;
            else if ((c == ',' || c == '}' || c == ']') && braces == 0 && brackets == 0 && parentheses == 0) return i;
        }
        return text.length();
    }

    /**
     * 跳过双引号、单引号或模板字符串。
     * 返回结束引号后一位下标；字符串未闭合时返回文本长度。
     */
    private static int skipQuoted(String text, int start, char quote) {
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == quote) return i + 1;
        }
        return text.length();
    }

    /**
     * 跳过 JavaScript 正则字面量，同时处理转义字符、字符组和尾部 flags。
     */
    private static int skipRegex(String text, int start) {
        boolean characterClass = false;
        int i = start + 1;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == '[') characterClass = true;
            else if (c == ']') characterClass = false;
            else if (c == '/' && !characterClass) {
                i++;
                while (i < text.length() && Character.isLetter(text.charAt(i))) i++;
                return i;
            }
        }
        return i;
    }

    /**
     * 根据斜杠之前的有效 token 判断它是正则起始符还是除法运算符。
     *
     * <p>例如 {@code return /abc/g} 中是正则，而 {@code value / 10000} 中是除法。
     * JavaScript 的词法上下文非常复杂，这里只覆盖工具参数中常见且可确定的前置符号和关键字。</p>
     */
    private static boolean isRegexStart(String text, int valueStart, int slash) {
        for (int i = slash - 1; i >= valueStart; i--) {
            char previous = text.charAt(i);
            if (Character.isWhitespace(previous)) continue;
            if ("([{,:;=!?&|+-*%^~<>".indexOf(previous) >= 0) {
                return true;
            }
            if (isIdentifierPart(previous)) {
                int end = i + 1;
                while (i >= valueStart && isIdentifierPart(text.charAt(i))) i--;
                String keyword = text.substring(i + 1, end);
                return isRegexPrefixKeyword(keyword);
            }
            return false;
        }
        return true;
    }

    /**
     * 这些 JavaScript 关键字之后允许直接出现正则字面量。
     */
    private static boolean isRegexPrefixKeyword(String keyword) {
        return "return".equals(keyword) || "throw".equals(keyword) || "case".equals(keyword)
            || "delete".equals(keyword) || "typeof".equals(keyword) || "void".equals(keyword)
            || "yield".equals(keyword) || "await".equals(keyword) || "instanceof".equals(keyword)
            || "in".equals(keyword) || "of".equals(keyword);
    }

    /**
     * 跳过单行或多行注释。kind 为 '/' 表示单行注释，为 '*' 表示多行注释。
     */
    private static int skipComment(String text, int start, char kind) {
        if (kind == '/') {
            int end = text.indexOf('\n', start + 2);
            return end < 0 ? text.length() : end;
        }
        int end = text.indexOf("*/", start + 2);
        return end < 0 ? text.length() : end + 2;
    }

    /**
     * 去掉单引号字符串的外层引号，并还原最基本的引号转义。
     */
    private static String unquote(String value) {
        String body = value.substring(1, value.length() - 1);
        return body.replace("\\'", "'").replace("\\\"", "\"");
    }

    /**
     * 在指定位置匹配完整关键字，避免把 functional 等标识符误识别为 function。
     */
    private static boolean matchesWord(String text, int start, String word) {
        int end = start + word.length();
        return end <= text.length() && text.regionMatches(start, word, 0, word.length())
            && (end == text.length() || !isIdentifierPart(text.charAt(end)));
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
