/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提交给 {@link SkillRuntime} 的一次命令执行请求。
 *
 * <p>请求对象不可变，可安全地在调用链中传递。命令字符串由 Runtime 交给其 Shell
 * 执行；{@code workingDirectory} 和环境变量也都作用于 Runtime 内部，而不是固定指向
 * 当前 Java 进程。</p>
 */
public class SkillExecutionRequest {

    private final String command;
    private final String workingDirectory;
    private final long timeoutMillis;
    private final Map<String, String> environment;

    /**
     * 创建执行请求。
     *
     * @param command 要执行的 Shell 命令，不能为空
     * @param workingDirectory Runtime 内的工作目录；为 {@code null} 时由实现决定
     * @param timeoutMillis 最长执行时间，必须大于 0
     * @param environment 附加环境变量；允许为 {@code null}，构造后会复制并只读化
     */
    public SkillExecutionRequest(String command, String workingDirectory, long timeoutMillis,
                                 Map<String, String> environment) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be greater than zero");
        }
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.timeoutMillis = timeoutMillis;
        this.environment = environment == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    }

    /** @return Shell 命令文本 */
    public String getCommand() {
        return command;
    }

    /** @return Runtime 内的工作目录，可能为 {@code null} */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /** @return 超时时间，单位毫秒 */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /** @return 不可变的附加环境变量 */
    public Map<String, String> getEnvironment() {
        return environment;
    }
}
