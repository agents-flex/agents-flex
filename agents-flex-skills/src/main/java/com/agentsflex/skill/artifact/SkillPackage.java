/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact;

import java.io.IOException;
import java.io.InputStream;

/**
 * 待安装 Skill 包的存储无关输入。
 *
 * <p>每次调用 {@link #openStream()} 都必须返回一个位于内容起点的新流，流由调用方关闭。
 * 当前文件系统 Store 支持 ZIP 包，并要求 {@code SKILL.md} 位于压缩包根目录。</p>
 */
public interface SkillPackage {

    InputStream openStream() throws IOException;

    String getFileName();

    long getSize();
}
