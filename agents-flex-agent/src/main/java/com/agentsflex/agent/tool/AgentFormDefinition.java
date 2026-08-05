/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * request_user_input 可以请求的一种业务表单。
 *
 * <p>formKey 和 whenToUse 用于生成模型可见的表单选项；schema 不发送给模型，只在模型选中表单后
 * 保存到 Suspension，并通过 AgentFormMessage 提供给前端。Schema 应使用可序列化的 JSON 值。</p>
 */
public final class AgentFormDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String formKey;
    private final String whenToUse;
    private final Map<String, Object> schema;

    private AgentFormDefinition(Builder builder) {
        this.formKey = builder.formKey;
        this.whenToUse = builder.whenToUse;
        this.schema = immutableMap(builder.schema);
    }

    /** 创建指定稳定表单标识的构建器。 */
    public static Builder builder(String formKey) {
        return new Builder(formKey);
    }

    public String getFormKey() {
        return formKey;
    }

    public String getWhenToUse() {
        return whenToUse;
    }

    /** @return 不可修改的 JSON Schema */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /** 表单定义构建器。 */
    public static final class Builder {
        private final String formKey;
        private String whenToUse;
        private Map<String, Object> schema;

        private Builder(String formKey) {
            this.formKey = formKey;
        }

        /** 设置模型选择该表单的业务条件，该说明会出现在 Tool description 中。 */
        public Builder whenToUse(String value) {
            this.whenToUse = value;
            return this;
        }

        /** 设置只提供给 Runner 和前端的 JSON Schema。 */
        public Builder schema(Map<String, ?> value) {
            this.schema = value == null ? null : new LinkedHashMap<String, Object>(value);
            return this;
        }

        public AgentFormDefinition build() {
            if (!StringUtil.hasText(formKey)) {
                throw new IllegalStateException("formKey must not be blank");
            }
            if (!StringUtil.hasText(whenToUse)) {
                throw new IllegalStateException("whenToUse must not be blank: " + formKey);
            }
            if (schema == null || schema.isEmpty()) {
                throw new IllegalStateException("schema must not be empty: " + formKey);
            }
            return new AgentFormDefinition(this);
        }
    }

    private static Map<String, Object> immutableMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }
}
