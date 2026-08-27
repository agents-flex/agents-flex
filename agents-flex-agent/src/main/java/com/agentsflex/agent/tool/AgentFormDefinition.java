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
 * <p>formKey 和 description 用于生成模型可见的表单选项；schema 不发送给模型，只在模型选中表单后
 * 保存到 Suspension，并通过 AgentFormMessage 提供给前端。Schema 应使用可序列化的 JSON 值。</p>
 */
public final class AgentFormDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String formKey;
    private final String description;
    private final Map<String, Object> schema;

    /**
     * 从已校验构建器创建不可变表单定义，并冻结 Schema。
     */
    private AgentFormDefinition(Builder builder) {
        this.formKey = builder.formKey;
        this.description = builder.description;
        this.schema = immutableMap(builder.schema);
    }

    /**
     * 创建指定稳定表单标识的构建器。
     */
    public static Builder builder(String formKey) {
        return new Builder(formKey);
    }

    public String getFormKey() {
        return formKey;
    }

    /**
     * @return 提供给模型的表单用途描述，用于帮助模型选择合适的表单
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 不可修改的 JSON Schema
     */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /**
     * 表单定义构建器。
     */
    public static final class Builder {
        private final String formKey;
        private String description;
        private Map<String, Object> schema;

        /**
         * @param formKey 业务侧稳定表单标识
         */
        private Builder(String formKey) {
            this.formKey = formKey;
        }

        /**
         * 设置表单的用途描述，该说明会出现在 Tool description 中，供模型选择表单。
         */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /**
         * 设置只提供给 Runner 和前端的 JSON Schema。
         */
        public Builder schema(Map<String, ?> value) {
            this.schema = value == null ? null : new LinkedHashMap<String, Object>(value);
            return this;
        }

        /**
         * 校验标识、模型描述和 JSON Schema 后创建表单定义。
         *
         * @return 不可变表单定义
         * @throws IllegalStateException 任一必填配置缺失时抛出
         */
        public AgentFormDefinition build() {
            if (!StringUtil.hasText(formKey)) {
                throw new IllegalStateException("formKey must not be blank");
            }
            if (!StringUtil.hasText(description)) {
                throw new IllegalStateException("description must not be blank: " + formKey);
            }
            if (schema == null || schema.isEmpty()) {
                throw new IllegalStateException("schema must not be empty: " + formKey);
            }
            return new AgentFormDefinition(this);
        }
    }

    /**
     * 复制 Schema 并返回保持字段顺序的不可修改 Map。
     */
    private static Map<String, Object> immutableMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }
}
