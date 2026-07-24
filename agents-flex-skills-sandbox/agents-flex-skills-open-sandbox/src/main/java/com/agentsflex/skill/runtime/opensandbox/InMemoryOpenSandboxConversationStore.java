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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 单 JVM 默认会话 Store，适合开发、测试和单实例部署。 */
public final class InMemoryOpenSandboxConversationStore implements OpenSandboxConversationStore {

    private static final InMemoryOpenSandboxConversationStore SHARED =
        new InMemoryOpenSandboxConversationStore();

    private final ConcurrentMap<String, OpenSandboxConversationRecord> records = new ConcurrentHashMap<>();

    /** 返回 Runtime 默认共享的单例 Store。 */
    public static InMemoryOpenSandboxConversationStore shared() {
        return SHARED;
    }

    @Override
    public OpenSandboxConversationRecord get(OpenSandboxConversationKey key) {
        return records.get(key.getStorageKey());
    }

    @Override
    public boolean create(OpenSandboxConversationRecord record) {
        return records.putIfAbsent(record.getKey().getStorageKey(), record) == null;
    }

    @Override
    public void update(OpenSandboxConversationRecord record) {
        String storageKey = record.getKey().getStorageKey();
        OpenSandboxConversationRecord existing = records.get(storageKey);
        if (existing != null && !existing.getWorkspaceRoot().equals(record.getWorkspaceRoot())) {
            throw new IllegalStateException("The same conversation key must use the same conversationsRoot");
        }
        if (records.replace(storageKey, record) == null) {
            throw new OpenSandboxConversationStoreException(
                "OpenSandbox conversation does not exist: " + storageKey, null);
        }
    }

    @Override
    public OpenSandboxConversationRecord delete(OpenSandboxConversationKey key) {
        return records.remove(key.getStorageKey());
    }
}
