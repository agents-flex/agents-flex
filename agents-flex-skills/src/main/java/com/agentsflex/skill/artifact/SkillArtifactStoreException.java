/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact;

/** Skill Artifact 无法读取、校验或物化时抛出的异常。 */
public class SkillArtifactStoreException extends RuntimeException {

    public SkillArtifactStoreException(String message) {
        super(message);
    }

    public SkillArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
