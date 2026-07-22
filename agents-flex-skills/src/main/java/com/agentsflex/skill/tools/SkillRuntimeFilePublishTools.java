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
import com.agentsflex.skill.file.FilePublishRequest;
import com.agentsflex.skill.file.FilePublisher;
import com.agentsflex.skill.file.PublishedFile;
import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;

import java.io.InputStream;
import java.net.URLConnection;
import java.util.Collections;

/**
 * 把 Runtime 文件发布为用户可访问 URL 的模型工具。
 *
 * <p>工具负责验证 Runtime 路径、打开并关闭输入流以及补充文件元数据；实际保存位置、
 * URL 生成、租户隔离和发布权限由应用提供的 {@link FilePublisher} 决定。</p>
 */
public class SkillRuntimeFilePublishTools {

    private final SkillRuntime runtime;
    private final SkillRuntimeFileSystem files;
    private final FilePublisher publisher;

    /**
     * @param runtime 产物所在 Runtime
     * @param publisher 应用提供的文件发布实现
     */
    public SkillRuntimeFilePublishTools(SkillRuntime runtime, FilePublisher publisher) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
        this.runtime = runtime;
        this.files = runtime.getFileSystem();
        this.publisher = publisher;
    }

    /**
     * 发布 Runtime 中的一个普通文件并返回 URL。
     *
     * @param filePath Runtime 内文件路径
     * @param fileName 可选展示文件名；为空时使用路径中的文件名
     * @param contentType 可选 MIME 类型；为空时根据文件名推断
     * @return 包含 URL 和文件信息的发布结果
     */
    @ToolDef(name = "publish_file", description = "Publishes a file from the configured skill runtime "
        + "and returns a URL that can be delivered to the user. Use this after generating and validating "
        + "a final file. The path must reference a regular file inside the configured runtime.")
    public String publishFile(
        @ToolParam(name = "filePath", description = "Absolute runtime-visible path of the file to publish")
        String filePath,
        @ToolParam(name = "fileName", description = "Optional user-facing file name", required = false)
        String fileName,
        @ToolParam(name = "contentType", description = "Optional MIME type", required = false)
        String contentType) {

        if (StringUtil.noText(filePath)) {
            return "Error: filePath must not be empty";
        }

        try {
            SkillFileInfo info = files.stat(filePath);
            if (info == null) {
                return "Error: File does not exist in " + runtime.getName() + " runtime: " + filePath;
            }
            if (info.isDirectory()) {
                return "Error: Only regular files can be published: " + filePath;
            }

            String resolvedFileName = resolveFileName(filePath, fileName);
            String resolvedContentType = StringUtil.hasText(contentType)
                ? contentType.trim() : URLConnection.guessContentTypeFromName(resolvedFileName);
            if (!StringUtil.hasText(resolvedContentType)) {
                resolvedContentType = "application/octet-stream";
            }

            PublishedFile publishedFile;
            try (InputStream input = files.openInputStream(filePath)) {
                FilePublishRequest request = FilePublishRequest.builder()
                    .inputStream(input)
                    .fileName(resolvedFileName)
                    .contentType(resolvedContentType)
                    .contentLength(info.getSize())
                    .sourcePath(filePath)
                    .runtimeName(runtime.getName())
                    .metadata(Collections.<String, Object>emptyMap())
                    .build();
                publishedFile = publisher.publish(request);
            }

            if (publishedFile == null) {
                return "Error: FilePublisher returned no result for: " + filePath;
            }
            return format(publishedFile, resolvedFileName, resolvedContentType, info.getSize());
        } catch (RuntimeException e) {
            return "Error publishing file from " + runtime.getName() + " runtime: " + e.getMessage();
        } catch (Exception e) {
            return "Error publishing file from " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    private static String resolveFileName(String filePath, String requestedName) {
        String value = StringUtil.hasText(requestedName) ? requestedName.trim() : filePath;
        String normalized = value.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String name = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("Unable to determine file name");
        }
        return name;
    }

    private static String format(PublishedFile publishedFile, String fallbackName,
                                 String fallbackContentType, long fallbackLength) {
        String name = StringUtil.hasText(publishedFile.getFileName())
            ? publishedFile.getFileName() : fallbackName;
        String type = StringUtil.hasText(publishedFile.getContentType())
            ? publishedFile.getContentType() : fallbackContentType;
        long length = publishedFile.getContentLength() >= 0
            ? publishedFile.getContentLength() : fallbackLength;

        StringBuilder result = new StringBuilder("File published successfully.\n")
            .append("URL: ").append(publishedFile.getUrl()).append('\n')
            .append("File name: ").append(name).append('\n')
            .append("Content type: ").append(type).append('\n')
            .append("Size: ").append(length).append(" bytes");
        if (publishedFile.getExpiresAt() != null) {
            result.append('\n').append("Expires at: ").append(publishedFile.getExpiresAt());
        }
        return result.toString();
    }
}
