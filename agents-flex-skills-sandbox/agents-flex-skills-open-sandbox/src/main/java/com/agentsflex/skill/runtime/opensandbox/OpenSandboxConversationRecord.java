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
package com.agentsflex.skill.runtime.opensandbox;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可跨 JVM 持久化的 OpenSandbox 会话状态。
 */
public final class OpenSandboxConversationRecord {

    private final OpenSandboxConversationKey key;
    private final String workspaceRoot;
    private final String sandboxId;
    private final boolean workspaceReady;
    private final Map<String, String> preparedSkills;

    public OpenSandboxConversationRecord(OpenSandboxConversationKey key, String workspaceRoot,
                                         String sandboxId, boolean workspaceReady,
                                         Map<String, String> preparedSkills) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceRoot must not be empty");
        }
        this.key = key;
        this.workspaceRoot = workspaceRoot;
        this.sandboxId = sandboxId == null || sandboxId.trim().isEmpty() ? null : sandboxId;
        this.workspaceReady = workspaceReady;
        this.preparedSkills = Collections.unmodifiableMap(new LinkedHashMap<>(preparedSkills == null
            ? Collections.<String, String>emptyMap() : preparedSkills));
    }

    public OpenSandboxConversationKey getKey() {
        return key;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public boolean isWorkspaceReady() {
        return workspaceReady;
    }

    public Map<String, String> getPreparedSkills() {
        return preparedSkills;
    }
}
