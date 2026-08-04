/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.context;

import java.util.Map;
import java.util.function.BiPredicate;

/**
 * 将工具结果外置判断与 Artifact Store 组合成一个可选运行能力。
 *
 * <p>Runner 未配置本对象时不会外置任何工具结果。组合后的能力保证判断逻辑与实际 Store 总是同时
 * 存在，避免只在 Agent 上配置阈值、却忘记为 Runner 提供持久化 Store。</p>
 */
public final class ToolResultOffloader {

    private final AgentArtifactStore artifactStore;
    private final BiPredicate<String, String> shouldOffload;

    private ToolResultOffloader(AgentArtifactStore artifactStore,
                                BiPredicate<String, String> shouldOffload) {
        if (artifactStore == null || shouldOffload == null) {
            throw new IllegalArgumentException("artifactStore and shouldOffload must not be null");
        }
        this.artifactStore = artifactStore;
        this.shouldOffload = shouldOffload;
    }

    /**
     * 创建按字符数外置大型工具结果的能力。
     *
     * <p>结果长度严格大于 {@code characterCount} 时才外置，等于阈值时仍保留在 ToolMessage 中。</p>
     */
    public static ToolResultOffloader largerThan(int characterCount,
                                                 AgentArtifactStore artifactStore) {
        if (characterCount <= 0) {
            throw new IllegalArgumentException("characterCount must be greater than 0");
        }
        return new ToolResultOffloader(artifactStore,
            (toolName, content) -> content != null && content.length() > characterCount);
    }

    /**
     * 使用自定义判断逻辑创建外置能力。
     */
    public static ToolResultOffloader when(AgentArtifactStore artifactStore,
                                           BiPredicate<String, String> shouldOffload) {
        return new ToolResultOffloader(artifactStore, shouldOffload);
    }

    /**
     * 按配置判断并保存工具结果；无需外置时返回 {@code null}。
     */
    public AgentArtifactReference offload(String runId, String toolName, String mediaType,
                                          String content, Map<String, ?> metadata) {
        return shouldOffload.test(toolName, content)
            ? artifactStore.save(runId, mediaType, content, metadata) : null;
    }

    /**
     * 读取已经外置的完整内容，不存在时返回 {@code null}。
     */
    public String load(String artifactId) {
        return artifactStore.load(artifactId);
    }

    /**
     * @return 该能力实际使用的 Artifact Store
     */
    public AgentArtifactStore getArtifactStore() {
        return artifactStore;
    }
}
