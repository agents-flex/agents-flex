/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact;

/**
 * 一个已安装 Skill 的不可变版本引用。
 *
 * <p>{@code storageKey} 的具体含义由 {@link SkillArtifactStore} 决定：文件系统实现可以
 * 将它解释为相对目录，对象存储实现则可以将它解释为对象 Key。{@code digest} 用于标识
 * 内容版本，并可由远程实现用于本地缓存和完整性校验。</p>
 */
public class SkillArtifact {

    private String name;
    private String version;
    private String digest;
    private String storageKey;
    private long size;

    public SkillArtifact() {
    }

    public SkillArtifact(String name, String version, String digest, String storageKey) {
        this(name, version, digest, storageKey, 0L);
    }

    public SkillArtifact(String name, String version, String digest, String storageKey, long size) {
        this.name = name;
        this.version = version;
        this.digest = digest;
        this.storageKey = storageKey;
        this.size = size;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
