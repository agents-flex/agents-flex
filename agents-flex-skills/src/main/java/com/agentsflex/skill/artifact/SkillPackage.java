/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
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
