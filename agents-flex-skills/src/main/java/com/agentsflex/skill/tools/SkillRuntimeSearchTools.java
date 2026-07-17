/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.tools;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Runtime 文件系统原语实现的 Glob 和 Grep 搜索工具。
 *
 * <p>搜索过程由 Java 完成，不依赖目标环境预装 {@code grep}、{@code find} 或
 * {@code ripgrep}。工具先通过 {@link SkillRuntimeFileSystem#listFiles(String, int, int)}
 * 获取候选文件，再应用 glob、文件类型、大小和忽略目录规则，使本地与远程 Runtime
 * 获得一致行为。</p>
 */
public class SkillRuntimeSearchTools {

    private static final int MAX_DEPTH = 100;
    private static final int MAX_FILES = 5000;
    private static final int MAX_GLOB_RESULTS = 1000;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_OUTPUT_LENGTH = 100000;
    private static final int MAX_LINE_LENGTH = 10000;
    private static final Set<String> IGNORED_SEGMENTS = new LinkedHashSet<>(Arrays.asList(
        ".git", "node_modules", "target", "build", ".idea", ".vscode", "dist", "__pycache__"));
    private static final Map<String, List<String>> TYPE_PATTERNS = typePatterns();

    private final SkillRuntime runtime;
    private final SkillRuntimeFileSystem files;

    /** @param runtime 文件搜索实际发生的 Runtime */
    public SkillRuntimeSearchTools(SkillRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        this.runtime = runtime;
        this.files = runtime.getFileSystem();
    }

    /**
     * 按 glob 模式查找 Runtime 内文件。
     *
     * @param pattern glob 模式，例如“任意目录下的所有 Python 文件”
     * @param path 可选起始目录；为空时使用 Runtime 默认工作目录
     * @return 按修改时间倒序排列的文件路径
     */
    @ToolDef(name = "Glob", description = "Finds files by glob pattern inside the configured skill runtime. "
        + "Supports patterns such as **/*.py and never searches the host for a remote runtime.")
    public String glob(
        @ToolParam(name = "pattern", description = "Glob pattern") String pattern,
        @ToolParam(name = "path", description = "Optional runtime-visible directory", required = false) String path) {
        if (StringUtil.noText(pattern)) {
            return "Error: The glob pattern must not be empty";
        }
        String root = defaultPath(path);
        try {
            List<SkillFileInfo> matches = new ArrayList<>();
            for (SkillFileInfo file : files.listFiles(root, MAX_DEPTH, MAX_FILES)) {
                if (!file.isDirectory() && !isIgnored(file.getPath(), root)
                    && matchesGlob(file.getPath(), root, pattern.trim())) {
                    matches.add(file);
                }
            }
            if (matches.isEmpty()) {
                return "No files found matching pattern: " + pattern;
            }
            matches.sort(Comparator.comparingLong(SkillFileInfo::getModifiedTimeMillis).reversed()
                .thenComparing(SkillFileInfo::getPath));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < Math.min(matches.size(), MAX_GLOB_RESULTS); i++) {
                result.append(matches.get(i).getPath()).append('\n');
            }
            return result.toString().trim();
        } catch (RuntimeException e) {
            return "Error executing glob in " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    /**
     * 使用 Java 正则表达式搜索 Runtime 内的 UTF-8 文本文件。
     *
     * <p>支持输出匹配内容、仅输出文件名或统计次数，并可按 glob、常见文件类型、上下文
     * 行、大小写和多行模式过滤。二进制文件、无法按文本读取的文件和超过大小限制的文件
     * 会被跳过。</p>
     *
     * @param pattern Java 正则表达式
     * @param path 可选的 Runtime 文件或目录
     * @param glob 可选 glob 文件过滤器
     * @param outputMode {@code content}、{@code files_with_matches} 或 {@code count}
     * @param contextBefore 匹配前上下文行数
     * @param contextAfter 匹配后上下文行数
     * @param context 同时设置匹配前后上下文行数
     * @param showLineNumbers 是否显示行号
     * @param caseInsensitive 是否忽略大小写
     * @param type 文件类型快捷过滤，例如 {@code java}、{@code py}、{@code md}
     * @param headLimit 最大结果条数
     * @param offset 跳过的结果条数
     * @param multiline 是否允许正则跨行匹配
     * @return 搜索结果或错误信息
     */
    @ToolDef(name = "Grep", description = "Searches UTF-8 file contents with a Java regular expression inside the configured skill runtime. "
        + "Supports content, files_with_matches, and count output modes.")
    public String grep(
        @ToolParam(name = "pattern", description = "Java regular expression") String pattern,
        @ToolParam(name = "path", description = "Optional runtime-visible file or directory", required = false) String path,
        @ToolParam(name = "glob", description = "Optional glob file filter", required = false) String glob,
        @ToolParam(name = "outputMode", description = "content, files_with_matches, or count", required = false)
        String outputMode,
        @ToolParam(name = "contextBefore", description = "Context lines before matches", required = false) Integer contextBefore,
        @ToolParam(name = "contextAfter", description = "Context lines after matches", required = false) Integer contextAfter,
        @ToolParam(name = "context", description = "Context lines before and after matches", required = false) Integer context,
        @ToolParam(name = "showLineNumbers", description = "Show line numbers; defaults to true", required = false)
        Boolean showLineNumbers,
        @ToolParam(name = "caseInsensitive", description = "Case-insensitive search", required = false)
        Boolean caseInsensitive,
        @ToolParam(name = "type", description = "Optional file type such as java, py, js, or md", required = false)
        String type,
        @ToolParam(name = "headLimit", description = "Maximum result entries", required = false) Integer headLimit,
        @ToolParam(name = "offset", description = "Result entries to skip", required = false) Integer offset,
        @ToolParam(name = "multiline", description = "Allow matches across lines", required = false) Boolean multiline) {
        if (StringUtil.noText(pattern)) {
            return "Error: The grep pattern must not be empty";
        }
        int flags = Pattern.MULTILINE;
        if (Boolean.TRUE.equals(caseInsensitive)) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (Boolean.TRUE.equals(multiline)) {
            flags |= Pattern.DOTALL;
        }
        final Pattern regex;
        try {
            regex = Pattern.compile(pattern, flags);
        } catch (RuntimeException e) {
            return "Error: Invalid regex pattern: " + e.getMessage();
        }

        String mode = StringUtil.hasText(outputMode) ? outputMode : "files_with_matches";
        if (!("content".equals(mode) || "files_with_matches".equals(mode) || "count".equals(mode))) {
            return "Error: Unsupported outputMode: " + mode;
        }
        int before = Math.max(0, context != null ? context : contextBefore == null ? 0 : contextBefore);
        int after = Math.max(0, context != null ? context : contextAfter == null ? 0 : contextAfter);
        int skip = Math.max(0, offset == null ? 0 : offset);
        int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;
        List<String> results = new ArrayList<>();
        String root = defaultPath(path);

        try {
            for (SkillFileInfo file : files.listFiles(root, MAX_DEPTH, MAX_FILES)) {
                if (file.isDirectory() || file.getSize() > MAX_FILE_BYTES || isIgnored(file.getPath(), root)
                    || !matchesFilters(file.getPath(), root, glob, type)) {
                    continue;
                }
                String content;
                try {
                    content = files.readText(file.getPath(), MAX_FILE_BYTES);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (Boolean.TRUE.equals(multiline)) {
                    addMultilineResults(results, file.getPath(), content, regex, mode);
                } else {
                    addLineResults(results, file.getPath(), content, regex, mode, before, after,
                        showLineNumbers == null || showLineNumbers);
                }
                if (results.size() >= skip + limit) {
                    break;
                }
            }
            if (results.isEmpty()) {
                return "No matches found for pattern: " + pattern;
            }
            int from = Math.min(skip, results.size());
            int to = Math.min(results.size(), from + limit);
            String joined = String.join("\n", results.subList(from, to));
            return joined.length() > MAX_OUTPUT_LENGTH
                ? joined.substring(0, MAX_OUTPUT_LENGTH) + "\n... (output truncated)" : joined;
        } catch (RuntimeException e) {
            return "Error executing grep in " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    private void addLineResults(List<String> results, String path, String content, Pattern regex, String mode,
                                int before, int after, boolean lineNumbers) {
        String[] lines = content.split("\\r?\\n", -1);
        List<Integer> matched = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() <= MAX_LINE_LENGTH) {
                Matcher matcher = regex.matcher(lines[i]);
                boolean found = false;
                while (matcher.find()) {
                    found = true;
                    count++;
                }
                if (found) {
                    matched.add(i);
                }
            }
        }
        if (matched.isEmpty()) {
            return;
        }
        if ("files_with_matches".equals(mode)) {
            results.add(path);
            return;
        }
        if ("count".equals(mode)) {
            results.add(path + ":" + count);
            return;
        }
        Set<Integer> emitted = new LinkedHashSet<>();
        for (Integer line : matched) {
            for (int i = Math.max(0, line - before); i <= Math.min(lines.length - 1, line + after); i++) {
                if (emitted.add(i)) {
                    String prefix = lineNumbers ? (i + 1) + (i == line ? ":  " : "- ") : "";
                    results.add(path + ":" + prefix + lines[i]);
                }
            }
        }
    }

    private void addMultilineResults(List<String> results, String path, String content, Pattern regex, String mode) {
        Matcher matcher = regex.matcher(content);
        int count = 0;
        List<String> snippets = new ArrayList<>();
        while (matcher.find()) {
            count++;
            if ("content".equals(mode)) {
                String value = matcher.group();
                snippets.add(path + ":" + value.substring(0, Math.min(value.length(), MAX_LINE_LENGTH)));
            }
        }
        if (count == 0) {
            return;
        }
        if ("files_with_matches".equals(mode)) {
            results.add(path);
        } else if ("count".equals(mode)) {
            results.add(path + ":" + count);
        } else {
            results.addAll(snippets);
        }
    }

    private String defaultPath(String path) {
        return StringUtil.hasText(path) ? path : runtime.getDefaultWorkingDirectory();
    }

    private static boolean matchesFilters(String file, String root, String glob, String type) {
        if (StringUtil.hasText(glob) && !matchesGlob(file, root, glob)) {
            return false;
        }
        if (StringUtil.hasText(type)) {
            List<String> patterns = TYPE_PATTERNS.get(type.toLowerCase());
            if (patterns != null) {
                for (String pattern : patterns) {
                    if (matchesGlob(file, root, pattern)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return true;
    }

    private static boolean matchesGlob(String file, String root, String pattern) {
        String normalizedFile = file.replace('\\', '/');
        String normalizedRoot = root.replace('\\', '/');
        String relative = normalizedFile.startsWith(normalizedRoot + "/")
            ? normalizedFile.substring(normalizedRoot.length() + 1) : normalizedFile;
        String effective = pattern.startsWith("**/") ? pattern : "**/" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + effective);
        Path relativePath = Paths.get(relative);
        return matcher.matches(relativePath) || matcher.matches(relativePath.getFileName())
            || FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(relativePath);
    }

    private static boolean isIgnored(String path, String root) {
        String normalizedPath = path.replace('\\', '/');
        String normalizedRoot = root.replace('\\', '/');
        String relative = normalizedPath.startsWith(normalizedRoot + "/")
            ? normalizedPath.substring(normalizedRoot.length() + 1) : normalizedPath;
        String normalized = "/" + relative + "/";
        for (String segment : IGNORED_SEGMENTS) {
            if (normalized.contains("/" + segment + "/")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<String>> typePatterns() {
        Map<String, List<String>> values = new HashMap<>();
        values.put("java", Arrays.asList("*.java", "*.jsp"));
        values.put("js", Arrays.asList("*.js", "*.jsx"));
        values.put("ts", Arrays.asList("*.ts", "*.tsx"));
        values.put("py", Collections.singletonList("*.py"));
        values.put("rust", Collections.singletonList("*.rs"));
        values.put("go", Collections.singletonList("*.go"));
        values.put("json", Collections.singletonList("*.json"));
        values.put("yaml", Arrays.asList("*.yaml", "*.yml"));
        values.put("md", Arrays.asList("*.md", "*.markdown"));
        values.put("sh", Arrays.asList("*.sh", "*.bash"));
        return values;
    }
}
