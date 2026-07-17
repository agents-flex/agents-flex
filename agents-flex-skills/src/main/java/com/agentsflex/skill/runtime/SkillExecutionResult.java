/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime;

/**
 * Runtime 命令的标准化执行结果。
 *
 * <p>本地进程、OpenSandbox 和 AIO Sandbox 的原始响应都会转换为该类型，使上层工具
 * 可以统一处理退出码、标准输出、标准错误和超时状态。</p>
 */
public class SkillExecutionResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;

    /**
     * @param exitCode 进程退出码；超时时可能是 Runtime 定义的非零值
     * @param stdout 标准输出，{@code null} 会转换为空字符串
     * @param stderr 标准错误，{@code null} 会转换为空字符串
     * @param timedOut 是否因达到超时限制而终止
     */
    public SkillExecutionResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.timedOut = timedOut;
    }

    /** @return 进程退出码 */
    public int getExitCode() {
        return exitCode;
    }

    /** @return 标准输出，永不为 {@code null} */
    public String getStdout() {
        return stdout;
    }

    /** @return 标准错误，永不为 {@code null} */
    public String getStderr() {
        return stderr;
    }

    /** @return 命令是否超时 */
    public boolean isTimedOut() {
        return timedOut;
    }
}
