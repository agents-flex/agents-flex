package com.agentsflex.core.model.chat;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * 内置协议只描述接口字段；模型是否支持开关应由连接的 supportThinking 声明。
 */
public enum ThinkingProtocols implements ThinkingProtocol {
    NONE {
        @Override
        public void apply(Boolean enabled, Map<String, Object> body) {
            if (enabled != null) {
                throw new IllegalArgumentException("Configure a thinking protocol before overriding thinkingEnabled");
            }
        }
    },
    QWEN {
        @Override
        public void apply(Boolean enabled, Map<String, Object> body) {
            if (enabled != null) body.put("enable_thinking", enabled);
        }

        @Override
        public boolean preserveReasoningContent(Boolean enabled) {
            return !Boolean.FALSE.equals(enabled);
        }
    },
    DEEPSEEK {
        @Override
        public void apply(Boolean enabled, Map<String, Object> body) {
            if (enabled != null) {
                body.put("thinking", Collections.singletonMap("type", enabled ? "enabled" : "disabled"));
            }
        }

        @Override
        public boolean preserveReasoningContent(Boolean enabled) {
            return !Boolean.FALSE.equals(enabled);
        }
    },
    /**
     * 适用于支持 medium / none 的 Chat Completions 模型；不代表所有 OpenAI 模型。
     */
    OPENAI {
        @Override
        public void apply(Boolean enabled, Map<String, Object> body) {
            if (enabled != null) body.put("reasoning_effort", enabled ? "medium" : "none");
        }
    },
    /**
     * Ollama 的 OpenAI 兼容接口。原生 /api/chat 应使用 OLLAMA_NATIVE。
     */
    OLLAMA {
        @Override
        public void apply(Boolean enabled, Map<String, Object> body) {
            OPENAI.apply(enabled, body);
        }
    },
    OLLAMA_NATIVE {
        @Override
        public void apply(Boolean enabled, Map<String, Object> body) {
            if (enabled != null) body.put("think", enabled);
        }
    };

    public static ThinkingProtocol resolve(String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) return NONE;
        try {
            return valueOf(protocol.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown thinking protocol: " + protocol, error);
        }
    }
}
