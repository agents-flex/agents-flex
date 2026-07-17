/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime;

/**
 * Runtime 文件系统中的文件元数据。
 *
 * <p>该对象只描述 Runtime 能看到的路径，不保证宿主机存在同名文件。Glob、Grep 等
 * 工具通过它遍历目录并区分普通文件与目录。</p>
 */
public class SkillFileInfo {

    private final String path;
    private final boolean directory;
    private final long size;
    private final long modifiedTimeMillis;

    /**
     * @param path Runtime 内的完整路径
     * @param directory 是否为目录
     * @param size 文件大小；目录或实现无法提供时可能为 0
     * @param modifiedTimeMillis 最后修改时间的 Unix 毫秒值；未知时可能为 0
     */
    public SkillFileInfo(String path, boolean directory, long size, long modifiedTimeMillis) {
        this.path = path;
        this.directory = directory;
        this.size = size;
        this.modifiedTimeMillis = modifiedTimeMillis;
    }

    /** @return Runtime 内路径 */
    public String getPath() {
        return path;
    }

    /** @return 是否为目录 */
    public boolean isDirectory() {
        return directory;
    }

    /** @return 文件大小，单位字节 */
    public long getSize() {
        return size;
    }

    /** @return 最后修改时间的 Unix 毫秒值 */
    public long getModifiedTimeMillis() {
        return modifiedTimeMillis;
    }
}
