/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.local;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.runtime.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 直接在宿主机执行 Skill 的本地 Runtime。
 *
 * <p>该实现主要用于开发、调试和可信脚本。它不会创建容器，也不会提供额外权限隔离：
 * Skill 中的命令拥有启动当前 Java 进程用户所拥有的文件和网络权限。处理不可信 Skill
 * 或用户输入时，应改用 OpenSandbox、AIO Sandbox 等隔离 Runtime。</p>
 *
 * <p>Unix-like 系统使用 {@code /bin/bash -c}，Windows 使用 {@code cmd.exe /c}。
 * 标准输出和标准错误由独立线程消费，以避免子进程因管道缓冲区写满而阻塞。</p>
 */
public class LocalSkillRuntime implements SkillRuntime {

    private final SkillRuntimeFileSystem fileSystem = new LocalSkillRuntimeFileSystem();

    @Override
    public String getName() {
        return "local";
    }

    @Override
    public List<Skill> prepare(List<Skill> skills) {
        // 本地执行无需上传，但返回新列表，避免调用方修改原始集合影响本次会话。
        return new ArrayList<>(skills);
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return new File("").getAbsoluteFile().toPath().normalize().toString();
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(request.getCommand()));
            builder.redirectErrorStream(false);
            builder.environment().putAll(request.getEnvironment());
            if (request.getWorkingDirectory() != null && !request.getWorkingDirectory().trim().isEmpty()) {
                builder.directory(new File(request.getWorkingDirectory()));
            }

            process = builder.start();
            // stdout 和 stderr 必须并行读取，否则任一管道写满都可能使子进程永久阻塞。
            StreamCollector stdout = new StreamCollector(process.getInputStream());
            StreamCollector stderr = new StreamCollector(process.getErrorStream());
            stdout.start();
            stderr.start();

            boolean completed = process.waitFor(request.getTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process);
            }
            stdout.join(1000);
            stderr.join(1000);
            return new SkillExecutionResult(completed ? process.exitValue() : -1,
                stdout.content(), stderr.content(), !completed);
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to execute local command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminate(process);
            }
            throw new SkillRuntimeException("Local command execution was interrupted", e);
        }
    }

    @Override
    public void close() {
        // 每次 execute 都独立创建并回收进程，因此 Runtime 自身没有常驻资源。
    }

    private static String[] shellCommand(String command) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return new String[]{"cmd.exe", "/c", command};
        }
        return new String[]{"/bin/bash", "-c", command};
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static class StreamCollector extends Thread {

        private final InputStream stream;
        private final StringBuilder content = new StringBuilder();

        private StreamCollector(InputStream stream) {
            this.stream = stream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 超时终止进程时流可能被异步关闭，此时已有输出仍然可以返回。
            }
        }

        private String content() {
            return content.toString();
        }
    }
}
