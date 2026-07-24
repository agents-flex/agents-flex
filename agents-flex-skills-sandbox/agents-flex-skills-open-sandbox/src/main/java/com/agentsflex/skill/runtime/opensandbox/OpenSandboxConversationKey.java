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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 用于持久化 OpenSandbox 会话的稳定键。
 */
public final class OpenSandboxConversationKey {

    private final String storageKey;
    private final String serviceKey;
    private final String conversationId;

    private OpenSandboxConversationKey(String serviceKey, String conversationId) {
        this.serviceKey = requireValue(serviceKey, "serviceKey", 128);
        this.conversationId = requireValue(conversationId, "conversationId", 128);
        this.storageKey = digest(this.serviceKey + "\n" + this.conversationId);
    }

    /**
     * 创建一个包含 OpenSandbox 服务和对话边界的会话键。
     */
    public static OpenSandboxConversationKey of(String serviceKey, String conversationId) {
        return new OpenSandboxConversationKey(serviceKey, conversationId);
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public String getConversationId() {
        return conversationId;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof OpenSandboxConversationKey)) {
            return false;
        }
        OpenSandboxConversationKey other = (OpenSandboxConversationKey) value;
        return storageKey.equals(other.storageKey);
    }

    @Override
    public int hashCode() {
        return storageKey.hashCode();
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                result.append(String.format("%02x", valueByte & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String requireValue(String value, String name, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
