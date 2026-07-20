/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
