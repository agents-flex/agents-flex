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
package com.agentsflex.skill.runtime.aiosandbox;

import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import com.alibaba.fastjson2.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AIO Sandbox 的 Runtime 文件系统实现。
 *
 * <p>文本读写使用 {@code /v1/file/read} 和 {@code /v1/file/write}；二进制下载使用
 * {@code /v1/file/download}。AIO 当前没有直接满足搜索所需的统一 stat/list 元数据 API，
 * 因此这两项能力通过受控 Shell 命令实现。</p>
 */
public class AioSandboxFileSystem implements SkillRuntimeFileSystem {

    private final AioSandboxClient client;

    /** @param client 已配置地址、鉴权和超时的 AIO 客户端 */
    public AioSandboxFileSystem(AioSandboxClient client) {
        this.client = client;
    }

    @Override
    public InputStream openInputStream(String path) {
        return client.downloadFile(path);
    }

    @Override
    public String readText(String path, int maxBytes) {
        JSONObject payload = new JSONObject();
        payload.put("file", path);
        String content = value(client.postData("/v1/file/read", payload, "read file"), "content");
        if (content.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new SkillRuntimeException("File exceeds read limit of " + maxBytes + " bytes: " + path);
        }
        return content;
    }

    @Override
    public void writeText(String path, String content) {
        Path parent = Paths.get(path).getParent();
        if (parent != null) {
            requireSuccessful(client.execute("mkdir -p " + shellQuote(normalize(parent)), "/", 30000),
                "create parent directory");
        }
        JSONObject payload = new JSONObject();
        payload.put("file", path);
        payload.put("content", content == null ? "" : content);
        payload.put("encoding", "utf-8");
        payload.put("append", false);
        client.post("/v1/file/write", payload, "write file");
    }

    @Override
    public SkillFileInfo stat(String path) {
        // 使用专用退出码 44 区分“路径不存在”和真正的 Shell 执行失败。
        String command = "if [ -f " + shellQuote(path) + " ]; then echo file; wc -c < " + shellQuote(path)
            + "; elif [ -d " + shellQuote(path) + " ]; then echo directory; echo 0; else exit 44; fi";
        SkillExecutionResult result = client.execute(command, "/", 30000);
        if (result.getExitCode() == 44) {
            return null;
        }
        requireSuccessful(result, "stat path");
        String[] lines = result.getStdout().trim().split("\\r?\\n");
        long size = lines.length > 1 ? parseLong(lines[1].trim()) : 0;
        return new SkillFileInfo(path, lines.length > 0 && "directory".equals(lines[0].trim()), size, 0);
    }

    @Override
    public List<SkillFileInfo> listDirectory(String path, int maxDepth, int maxResults) {
        SkillFileInfo info = stat(path);
        if (info == null) {
            throw new SkillRuntimeException("Path does not exist in AIO Sandbox: " + path);
        }
        if (!info.isDirectory()) {
            return Collections.singletonList(info);
        }
        String command = "find " + shellQuote(path) + " -mindepth 1 -maxdepth " + Math.max(1, maxDepth)
            + " -printf '%y\\t%p\\n'"
            + " | head -n " + Math.max(1, maxResults);
        SkillExecutionResult result = client.execute(command, "/", 30000);
        requireSuccessful(result, "list directory");
        List<SkillFileInfo> values = new ArrayList<>();
        for (String line : result.getStdout().split("\\r?\\n")) {
            int separator = line.indexOf('\t');
            if (separator > 0 && separator < line.length() - 1) {
                values.add(new SkillFileInfo(line.substring(separator + 1), line.charAt(0) == 'd', 0, 0));
            }
        }
        return values;
    }

    @Override
    public List<SkillFileInfo> listFiles(String path, int maxDepth, int maxResults) {
        SkillFileInfo info = stat(path);
        if (info == null) {
            throw new SkillRuntimeException("Path does not exist in AIO Sandbox: " + path);
        }
        if (!info.isDirectory()) {
            return Collections.singletonList(info);
        }
        // 参数均经过边界限制和 Shell 引号处理，避免路径改变命令结构。
        String command = "find " + shellQuote(path) + " -maxdepth " + Math.max(1, maxDepth)
            + " -type f -print | head -n " + Math.max(1, maxResults);
        SkillExecutionResult result = client.execute(command, "/", 30000);
        requireSuccessful(result, "list files");
        List<SkillFileInfo> values = new ArrayList<>();
        for (String line : result.getStdout().split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                values.add(new SkillFileInfo(line, false, 0, 0));
            }
        }
        return values;
    }

    static void requireSuccessful(SkillExecutionResult result, String operation) {
        if (result.isTimedOut() || result.getExitCode() != 0) {
            String detail = result.getStderr().isEmpty() ? result.getStdout() : result.getStderr();
            throw new SkillRuntimeException("AIO Sandbox failed to " + operation + ": " + detail);
        }
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String value(JSONObject object, String key) {
        String value = object.getString(key);
        return value == null ? "" : value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
