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
     * @param runtime   产物所在 Runtime
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
     * @param filePath    Runtime 内文件路径
     * @param fileName    可选展示文件名；为空时使用路径中的文件名
     * @param contentType 可选 MIME 类型；为空时根据文件名推断
     * @return 包含 URL 和文件信息的发布结果
     */
    @ToolDef(name = "publish_file", description = "Publishes an existing regular file from the configured "
        + "skill runtime and returns a user-accessible URL. Use this by default as the final delivery step "
        + "whenever the task produces a final file for the user, even if the user did not explicitly ask for "
        + "a URL or download link. Also use it for requests to send, give, attach, open, download, or share a "
        + "runtime file. Internal runtime paths are not user-accessible and must never be returned as the "
        + "delivery result. Do not publish temporary or intermediate files unless explicitly requested, and "
        + "do not publish when the user explicitly asks you not to. On success, include the returned URL in "
        + "the final response. If the tool returns an error, fix the problem and retry; do not claim that the "
        + "file was delivered.")
    public String publishFile(
        @ToolParam(name = "filePath", description = "Exact absolute runtime-visible path of an existing "
            + "regular file. Use a path returned or verified by runtime tools; do not invent a host path.")
        String filePath,
        @ToolParam(name = "fileName", description = "Optional file name shown to the user; path components "
            + "are removed", required = false)
        String fileName,
        @ToolParam(name = "contentType", description = "Optional MIME type; omit it to infer from the file "
            + "name", required = false)
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
        // 仅在存在失效时间时输出有效期，且换算成「多久之后」而不是毫秒时间戳：
        // 毫秒值模型无法直接转述给用户，天数/小时是可读口径。
        String validity = formatValidity(publishedFile.getExpiresAt());
        if (validity != null) {
            result.append('\n').append("Valid for: ").append(validity);
            result.append('\n').append("Note: this is a temporary link. It expires in ").append(validity)
                .append(", after which the file will be deleted and cannot be recovered. ")
                .append("Always tell the user how long the link stays valid and remind them to save the file in time.");
        }
        return result.toString();
    }

    /**
     * 把失效时间换算成人类可读的有效期。
     *
     * <p>口径：先四舍五入到小时，再拆成「天 + 小时」，避免恰好 N 天的有效期因为几毫秒误差
     * 被算成 N-1 天（发布后立刻调用时会剩余 N 天差几毫秒）。四舍五入的误差不超过 30 分钟。</p>
     *
     * @param expiresAt 失效时间的 Unix 毫秒值；{@code null} 表示永久地址或未知
     * @return 形如 {@code 7 days}、{@code 1 day 3 hours}、{@code 5 hours} 的描述；
     * 已过期返回 {@code expired}；入参为 {@code null} 时返回 {@code null}，表示不输出该行
     */
    private static String formatValidity(Long expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0) {
            return "expired";
        }
        long hours = Math.round(remaining / 3600000.0);
        if (hours <= 0) {
            return "less than 1 hour";
        }
        long days = hours / 24;
        long restHours = hours % 24;
        if (days == 0) {
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        String dayPart = days == 1 ? "1 day" : days + " days";
        if (restHours == 0) {
            return dayPart;
        }
        return dayPart + " " + (restHours == 1 ? "1 hour" : restHours + " hours");
    }
}
