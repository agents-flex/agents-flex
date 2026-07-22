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

import java.nio.file.Path;

/**
 * Skill 安装包的存储和本地物化边界。
 *
 * <p>实现负责确保返回目录在当前应用节点可读，并且在 Skill 使用期间保持稳定。本地实现
 * 可以直接返回安装目录；OSS、S3 等远程实现通常应下载、校验并解压到按 digest 命名的
 * 节点缓存目录。</p>
 */
public interface SkillArtifactStore {

    /**
     * 将一个 Skill 安装包持久化为 Artifact。
     *
     * <p>实现应在返回前完成写入，并且不能向并发读取者暴露半安装状态。远程实现可以在
     * 返回值中补充或规范化 {@code storageKey}、大小等存储信息。</p>
     *
     * @param request 待安装 Artifact 及存储无关的 Skill 包输入
     * @return 已持久化的 Artifact 引用
     * @throws SkillArtifactStoreException Artifact 无效或持久化失败
     */
    SkillArtifact install(SkillInstallRequest request);

    /**
     * 将指定 Artifact 物化为当前节点上的 Skill 根目录。
     *
     * @param artifact 已安装 Skill 的确定版本
     * @return 包含 {@code SKILL.md} 的稳定本地目录
     * @throws SkillArtifactStoreException Artifact 无效、下载失败或无法物化
     */
    Path materialize(SkillArtifact artifact);

    /**
     * 删除一个已持久化 Artifact。
     *
     * <p>删除不存在的 Artifact 应视为成功。Catalog 中的引用关系和版本状态应由调用方
     * 在调用本方法前处理。</p>
     *
     * @param artifact 待删除的 Artifact
     * @throws SkillArtifactStoreException 删除失败
     */
    void delete(SkillArtifact artifact);
}
