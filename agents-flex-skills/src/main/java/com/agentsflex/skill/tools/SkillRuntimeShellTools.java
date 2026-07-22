/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.tools;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntime;

import java.util.Collections;

/**
 * 完全委托给 {@link SkillRuntime} 的 bash 工具。
 *
 * <p>该工具不直接创建本机进程。Runtime 决定命令在宿主机、容器还是远程 Sandbox 中
 * 执行。工具层统一限制模型可设置的最长超时和返回文本长度，防止单次调用长期占用会话
 * 或把巨量日志塞入上下文。</p>
 */
public class SkillRuntimeShellTools {

    private static final long DEFAULT_TIMEOUT_MILLIS = 120000;
    private static final long MAX_TIMEOUT_MILLIS = 600000;
    private static final int MAX_OUTPUT_LENGTH = 30000;

    private final SkillRuntime runtime;

    /** @param runtime 接收所有命令的 Runtime */
    public SkillRuntimeShellTools(SkillRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        this.runtime = runtime;
    }

    /**
     * 在配置的 Runtime 中执行 Shell 命令。
     *
     * @param command Shell 命令文本
     * @param timeout 可选超时毫秒数，最终限制在 1 到 600000 之间
     * @param workingDirectory 可选的 Runtime 内工作目录
     * @return 包含退出码、超时状态、stdout 和 stderr 的格式化结果
     */
    @ToolDef(name = "bash", description = "Executes a shell command in the configured skill runtime. " +
        "Commands never bypass the runtime to execute on the host. The default timeout is 120000ms and the maximum is 600000ms.")
    public String bash(
        @ToolParam(name = "command", description = "The shell command to execute") String command,
        @ToolParam(name = "timeout", description = "Optional timeout in milliseconds", required = false) Long timeout,
        @ToolParam(name = "workingDirectory", description = "Optional runtime-visible working directory", required = false)
        String workingDirectory) {

        long timeoutMillis = timeout == null
            ? DEFAULT_TIMEOUT_MILLIS
            : Math.min(Math.max(timeout, 1), MAX_TIMEOUT_MILLIS);

        try {
            SkillExecutionResult execution = runtime.execute(new SkillExecutionRequest(command,
                workingDirectory, timeoutMillis, Collections.emptyMap()));
            return format(execution);
        } catch (RuntimeException e) {
            return "Error executing command in " + runtime.getName() + " runtime: " + e.getMessage();
        }
    }

    private static String format(SkillExecutionResult execution) {
        StringBuilder result = new StringBuilder();
        if (!execution.getStdout().isEmpty()) {
            result.append(execution.getStdout());
        }
        if (!execution.getStderr().isEmpty()) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append("STDERR:\n").append(execution.getStderr());
        }
        if (execution.isTimedOut()) {
            appendLine(result, "Command timed out");
        } else if (execution.getExitCode() != 0) {
            appendLine(result, "Exit code: " + execution.getExitCode());
        }
        if (result.length() > MAX_OUTPUT_LENGTH) {
            return result.substring(0, MAX_OUTPUT_LENGTH) + "\n... (output truncated)";
        }
        return result.toString();
    }

    private static void appendLine(StringBuilder result, String line) {
        if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
        result.append(line);
    }
}
