/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime.aiosandbox;

import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * AIO Sandbox Shell 与文件 API 的轻量 HTTP 客户端。
 *
 * <p>客户端使用 JDK {@link HttpURLConnection}，不额外引入 HTTP 框架。启用 JWT 时，
 * 每个请求都会携带 {@code Authorization: Bearer <token>}。Shell API 可能先返回
 * {@code running}，客户端会通过 wait/view 轮询直到完成或达到调用方超时。</p>
 *
 * <p>二进制下载返回与 HTTP 连接绑定的流；关闭流会自动断开连接，调用方必须遵守这一
 * 资源约定。</p>
 */
public class AioSandboxClient {

    private static final int MAX_POLL_SECONDS = 30;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String baseUrl;
    private final String bearerToken;
    private final int httpTimeoutMillis;

    /**
     * @param baseUrl AIO 服务根地址，例如 {@code http://localhost:8080}
     * @param bearerToken 可选 JWT；未启用鉴权时可以为 {@code null}
     * @param httpTimeoutMillis HTTP 读取超时，必须大于 0
     */
    public AioSandboxClient(String baseUrl, String bearerToken, int httpTimeoutMillis) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        if (httpTimeoutMillis <= 0) {
            throw new IllegalArgumentException("httpTimeoutMillis must be greater than zero");
        }
        this.bearerToken = bearerToken;
        this.httpTimeoutMillis = httpTimeoutMillis;
    }

    /**
     * 将标准 Runtime 执行请求转换为 AIO Shell API 调用。
     *
     * @param request Runtime 执行请求
     * @param defaultWorkingDirectory 请求未指定目录时使用的 AIO 工作目录
     * @return 标准化执行结果
     */
    public SkillExecutionResult execute(SkillExecutionRequest request, String defaultWorkingDirectory) {
        long hardTimeoutSeconds = Math.max(1, (request.getTimeoutMillis() + 999) / 1000);
        long softTimeoutSeconds = Math.min(hardTimeoutSeconds, MAX_POLL_SECONDS);

        JSONObject payload = new JSONObject();
        payload.put("command", commandWithEnvironment(request));
        payload.put("exec_dir", request.getWorkingDirectory() == null
            ? defaultWorkingDirectory : request.getWorkingDirectory());
        payload.put("async_mode", false);
        payload.put("timeout", softTimeoutSeconds);

        return awaitResult(postData("/v1/shell/exec", payload, "execute command"), request.getTimeoutMillis());
    }

    /**
     * 执行一个不附带环境变量的命令，供文件系统和上传器内部使用。
     */
    public SkillExecutionResult execute(String command, String workingDirectory, long timeoutMillis) {
        return execute(new SkillExecutionRequest(command, workingDirectory, timeoutMillis,
            Collections.<String, String>emptyMap()), workingDirectory);
    }

    /**
     * 发送 POST 请求并提取统一响应中的 {@code data} 对象。
     */
    public JSONObject postData(String path, JSONObject payload, String operation) {
        JSONObject data = post(path, payload, operation).getJSONObject("data");
        if (data == null) {
            throw new SkillRuntimeException("AIO Sandbox failed to " + operation + ": missing data");
        }
        return data;
    }

    /**
     * 发送 AIO JSON POST 请求并验证 HTTP 状态和 {@code success} 字段。
     */
    public JSONObject post(String path, JSONObject payload, String operation) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(Math.min(httpTimeoutMillis, 30000));
            connection.setReadTimeout(httpTimeoutMillis);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            connection.setDoOutput(true);
            byte[] requestBytes = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
            String text = stream == null ? "" : readUtf8(stream);
            if (status < 200 || status >= 300) {
                throw new SkillRuntimeException("AIO Sandbox failed to " + operation
                    + ": HTTP " + status + " " + text);
            }
            JSONObject root = JSON.parseObject(text);
            if (root == null || !root.getBooleanValue("success")) {
                throw new SkillRuntimeException("AIO Sandbox failed to " + operation + ": "
                    + (root == null ? "invalid JSON response" : root.getString("message")));
            }
            return root;
        } catch (IOException e) {
            throw new SkillRuntimeException("AIO Sandbox failed to " + operation, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 通过 {@code /v1/file/download} 打开远程文件下载流。
     *
     * @param path AIO Sandbox 内文件路径
     * @return 必须由调用方关闭的响应流
     */
    public InputStream downloadFile(String path) {
        HttpURLConnection connection = null;
        try {
            String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.name());
            connection = (HttpURLConnection) new URL(baseUrl + "/v1/file/download?path=" + encodedPath)
                .openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Math.min(httpTimeoutMillis, 30000));
            connection.setReadTimeout(httpTimeoutMillis);
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                String text = error == null ? "" : readUtf8(error);
                connection.disconnect();
                throw new SkillRuntimeException("AIO Sandbox failed to download file: HTTP "
                    + status + " " + text);
            }
            final HttpURLConnection activeConnection = connection;
            return new FilterInputStream(connection.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        activeConnection.disconnect();
                    }
                }
            };
        } catch (IOException e) {
            if (connection != null) {
                connection.disconnect();
            }
            throw new SkillRuntimeException("AIO Sandbox failed to download file: " + path, e);
        }
    }

    private SkillExecutionResult awaitResult(JSONObject initial, long timeoutMillis) {
        String sessionId = initial.getString("session_id");
        String status = initial.getString("status");
        String output = value(initial, "output");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while ("running".equals(status) && System.nanoTime() < deadline) {
            // 单次长轮询最多 30 秒，循环同时受调用方整体 deadline 约束。
            long remainingSeconds = Math.max(1,
                TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()) + 1);
            JSONObject waitPayload = new JSONObject();
            waitPayload.put("id", sessionId);
            waitPayload.put("seconds", Math.min(remainingSeconds, MAX_POLL_SECONDS));
            postData("/v1/shell/wait", waitPayload, "wait for command");

            JSONObject viewPayload = new JSONObject();
            viewPayload.put("id", sessionId);
            JSONObject data = postData("/v1/shell/view", viewPayload, "read command output");
            output = value(data, "output");
            status = data.getString("status");
            initial = data;
        }

        if ("running".equals(status)) {
            kill(sessionId);
            return new SkillExecutionResult(-1, output, "", true);
        }
        Integer exitCode = initial.getInteger("exit_code");
        return new SkillExecutionResult(exitCode == null ? -1 : exitCode,
            output, "", "timed_out".equals(status));
    }

    private void kill(String sessionId) {
        if (sessionId == null) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("id", sessionId);
        try {
            post("/v1/shell/kill", payload, "terminate timed-out command");
        } catch (RuntimeException ignored) {
            // 清理失败不能覆盖原始超时结果。
        }
    }

    private static String commandWithEnvironment(SkillExecutionRequest request) {
        // AIO 使用持久交互式 Shell。用子 Shell 包裹命令，避免顶层 exit 提前终止会话，
        // 导致服务端来不及采集退出状态。
        StringBuilder command = new StringBuilder("(\n");
        for (Map.Entry<String, String> entry : request.getEnvironment().entrySet()) {
            String name = entry.getKey();
            if (name == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new SkillRuntimeException("Invalid environment variable name: " + name);
            }
            command.append("export ").append(name).append('=')
                .append(AioSandboxFileSystem.shellQuote(entry.getValue() == null ? "" : entry.getValue()))
                .append('\n');
        }
        return command.append(request.getCommand()).append("\n)").toString();
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String value(JSONObject object, String key) {
        String value = object.getString(key);
        return value == null ? "" : value;
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl must not be empty");
        }
        String normalized = url.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URL parsed = new URL(normalized);
            if (!("http".equals(parsed.getProtocol()) || "https".equals(parsed.getProtocol()))
                || parsed.getHost() == null || parsed.getHost().isEmpty()) {
                throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL");
            }
            return normalized;
        } catch (IOException e) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL", e);
        }
    }
}
