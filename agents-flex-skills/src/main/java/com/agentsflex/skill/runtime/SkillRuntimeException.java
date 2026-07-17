/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime;

/**
 * Skill Runtime 在准备资源、执行命令或访问文件时抛出的统一运行时异常。
 *
 * <p>该异常用于屏蔽本地 I/O、远程 HTTP 和 Sandbox SDK 的实现差异，同时保留原始
 * cause 供日志和故障排查使用。</p>
 */
public class SkillRuntimeException extends RuntimeException {

    /** @param message 可直接用于定位 Runtime 操作的错误信息 */
    public SkillRuntimeException(String message) {
        super(message);
    }

    /**
     * @param message 可直接用于定位 Runtime 操作的错误信息
     * @param cause 底层异常
     */
    public SkillRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
