/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime;

import com.agentsflex.skill.Skill;

import java.util.List;

/**
 * Skill 资源、命令和文件操作的统一执行边界。
 *
 * <p>Runtime 可以代表当前主机、容器或远程沙箱。上层代码只依赖本接口，因此切换
 * Runtime 时无需改写 bash、read、write、glob、grep 等工具。</p>
 *
 * <p>{@link #prepare(List)} 接收当前会话配置的全部 Skill。实现必须让这些资源在目标
 * 环境中可用，并按输入顺序返回数量相同的 Skill；返回对象的 {@code basePath} 必须是
 * Runtime 内可访问的路径。远程实现通常会在这里上传目录，本地实现则可以直接返回副本。</p>
 *
 * <p>Runtime 具有生命周期。调用方应使用 try-with-resources 或在会话结束时调用
 * {@link #close()}，以释放沙箱、HTTP 连接或其他资源。</p>
 */
public interface SkillRuntime extends AutoCloseable {

    /** @return 稳定的 Runtime 名称，用于日志、配置和分支判断 */
    String getName();

    /**
     * 准备一批 Skills。
     *
     * @param skills 从宿主机或 classpath 发现的 Skill 列表
     * @return 与输入顺序和数量一致、路径已转换为 Runtime 可见路径的 Skill 列表
     */
    List<Skill> prepare(List<Skill> skills);

    /** @return Runtime 内执行命令时使用的默认工作目录 */
    String getDefaultWorkingDirectory();

    /** @return 与当前 Runtime 使用相同传输和权限边界的文件系统实现 */
    SkillRuntimeFileSystem getFileSystem();

    /**
     * 在 Runtime 内执行命令。
     *
     * @param request 执行参数
     * @return 标准化执行结果
     */
    SkillExecutionResult execute(SkillExecutionRequest request);

    /**
     * 释放 Runtime 资源。实现应允许在正常完成和异常路径中调用。
     */
    @Override
    void close();
}
