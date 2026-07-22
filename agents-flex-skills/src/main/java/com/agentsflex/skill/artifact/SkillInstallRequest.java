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

/** 一个确定 Artifact 版本及其安装包输入。 */
public class SkillInstallRequest {

    private SkillArtifact artifact;
    private SkillPackage skillPackage;

    public SkillInstallRequest() {
    }

    public SkillInstallRequest(SkillArtifact artifact, SkillPackage skillPackage) {
        this.artifact = artifact;
        this.skillPackage = skillPackage;
    }

    public SkillArtifact getArtifact() {
        return artifact;
    }

    public void setArtifact(SkillArtifact artifact) {
        this.artifact = artifact;
    }

    public SkillPackage getSkillPackage() {
        return skillPackage;
    }

    public void setSkillPackage(SkillPackage skillPackage) {
        this.skillPackage = skillPackage;
    }
}
