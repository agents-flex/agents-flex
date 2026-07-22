/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.tools;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;

import java.nio.charset.StandardCharsets;

/**
 * 基于 {@link SkillRuntime} 的模型文本文件工具。
 *
 * <p>提供 read、write 和 edit 三个工具。所有路径都交给 Runtime 文件系统处理；配置远程
 * Runtime 后不会回退到宿主机。为避免超大文件占满模型上下文，读取和编辑分别设置了
 * 字节、行数和单行长度上限。</p>
 */
public class SkillRuntimeFileTools {

    private static final int DEFAULT_READ_LINES = 2000;
    private static final int MAX_READ_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EDIT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LINE_LENGTH = 2000;

    private final SkillRuntime runtime;
    private final SkillRuntimeFileSystem files;

    /** @param runtime 文件实际所在的 Runtime */
    public SkillRuntimeFileTools(SkillRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        this.runtime = runtime;
        this.files = runtime.getFileSystem();
    }

    /**
     * 读取 UTF-8 文本并返回带行号的片段。
     *
     * @param filePath Runtime 内绝对路径
     * @param offset 可选的起始行，按 1 开始
     * @param limit 可选的最大行数
     * @return 适合直接返回给模型的文本或错误信息
     */
    @ToolDef(name = "read", description = "Reads a UTF-8 text file from the configured skill runtime. "
        + "The path is resolved inside that runtime and never against the host when a remote runtime is configured. "
        + "Returns numbered lines; defaults to 2000 lines.")
    public String read(
        @ToolParam(name = "filePath", description = "Absolute runtime-visible file path") String filePath,
        @ToolParam(name = "offset", description = "Optional 1-based starting line", required = false) Integer offset,
        @ToolParam(name = "limit", description = "Optional maximum number of lines", required = false) Integer limit) {
        try {
            SkillFileInfo info = files.stat(filePath);
            if (info == null) {
                return "Error: File does not exist in " + runtime.getName() + " runtime: " + filePath;
            }
            if (info.isDirectory()) {
                return "Error: Path is a directory, not a file: " + filePath;
            }
            String content = files.readText(filePath, MAX_READ_BYTES);
            String[] lines = content.split("\\r?\\n", -1);
            int totalLines = content.isEmpty() ? 0 : lines.length;
            int start = Math.max(1, offset == null ? 1 : offset);
            int max = Math.max(1, limit == null ? DEFAULT_READ_LINES : limit);
            if (start > totalLines) {
                return totalLines == 0 ? "File is empty: " + filePath
                    : "No lines to read. File has " + totalLines + " lines, but offset was " + start;
            }
            int end = Math.min(totalLines, start + max - 1);
            StringBuilder result = new StringBuilder("File: ").append(filePath)
                .append("\nShowing lines ").append(start).append('-').append(end)
                .append(" of ").append(totalLines).append("\n\n");
            for (int i = start; i <= end; i++) {
                String line = lines[i - 1];
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "... (line truncated)";
                }
                result.append(String.format("%6d\t%s%n", i, line));
            }
            return result.toString();
        } catch (RuntimeException e) {
            return "Error reading file from " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    /**
     * 创建或覆盖 Runtime 内的 UTF-8 文本文件。
     *
     * @param filePath Runtime 内绝对路径
     * @param content 写入内容；{@code null} 按空字符串处理
     * @return 写入结果摘要
     */
    @ToolDef(name = "write", description = "Creates or overwrites a UTF-8 text file in the configured skill runtime. "
        + "Parent directories are created by the runtime implementation.")
    public String write(
        @ToolParam(name = "filePath", description = "Absolute runtime-visible file path") String filePath,
        @ToolParam(name = "content", description = "UTF-8 text content") String content) {
        try {
            boolean existed = files.stat(filePath) != null;
            String value = content == null ? "" : content;
            files.writeText(filePath, value);
            return String.format("Successfully %s file in %s runtime: %s (%d bytes)",
                existed ? "overwrote" : "created", runtime.getName(), filePath,
                value.getBytes(StandardCharsets.UTF_8).length);
        } catch (RuntimeException e) {
            return "Error writing file in " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    /**
     * 对 Runtime 文件执行精确字符串替换。
     *
     * <p>默认要求旧字符串只出现一次，避免模型使用过短片段误改多处。需要批量替换时必须
     * 显式传入 {@code replaceAll=true}。</p>
     *
     * @param filePath Runtime 内绝对路径
     * @param oldString 必须精确匹配的原文本
     * @param newString 替换文本；{@code null} 表示删除
     * @param replaceAll 是否替换所有匹配项
     * @return 编辑结果及修改位置附近的文本片段
     */
    @ToolDef(name = "edit", description = "Performs an exact text replacement in a UTF-8 file inside the configured skill runtime.")
    public String edit(
        @ToolParam(name = "filePath", description = "Absolute runtime-visible file path") String filePath,
        @ToolParam(name = "old_string", description = "Exact text to replace") String oldString,
        @ToolParam(name = "new_string", description = "Replacement text") String newString,
        @ToolParam(name = "replace_all", description = "Replace every occurrence; defaults to false", required = false)
        Boolean replaceAll) {
        try {
            if (oldString == null || oldString.isEmpty()) {
                return "Error: old_string must not be empty";
            }
            if (oldString.equals(newString)) {
                return "Error: old_string and new_string must be different";
            }
            SkillFileInfo info = files.stat(filePath);
            if (info == null || info.isDirectory()) {
                return "Error: File does not exist in " + runtime.getName() + " runtime: " + filePath;
            }
            String original = files.readText(filePath, MAX_EDIT_BYTES);
            int occurrences = countOccurrences(original, oldString);
            if (occurrences == 0) {
                return "Error: old_string not found in file: " + filePath;
            }
            if (!Boolean.TRUE.equals(replaceAll) && occurrences > 1) {
                return "Error: old_string appears " + occurrences
                    + " times. Use a more specific string or set replace_all=true.";
            }
            String replacement = newString == null ? "" : newString;
            String updated = Boolean.TRUE.equals(replaceAll)
                ? original.replace(oldString, replacement)
                : replaceFirst(original, oldString, replacement);
            files.writeText(filePath, updated);
            return "The file " + filePath + " was updated in " + runtime.getName() + " runtime.\n"
                + editSnippet(updated, replacement);
        } catch (RuntimeException e) {
            return "Error editing file in " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static String replaceFirst(String text, String oldValue, String newValue) {
        int index = text.indexOf(oldValue);
        return text.substring(0, index) + newValue + text.substring(index + oldValue.length());
    }

    private static String editSnippet(String content, String inserted) {
        String[] lines = content.split("\\r?\\n", -1);
        int match = 0;
        if (!inserted.isEmpty()) {
            int characterIndex = content.indexOf(inserted);
            if (characterIndex >= 0) {
                match = content.substring(0, characterIndex).split("\\r?\\n", -1).length - 1;
            }
        }
        int start = Math.max(0, match - 3);
        int end = Math.min(lines.length - 1, match + 3);
        StringBuilder snippet = new StringBuilder();
        for (int i = start; i <= end; i++) {
            snippet.append(String.format("%6d\t%s%n", i + 1, lines[i]));
        }
        return snippet.toString();
    }
}
