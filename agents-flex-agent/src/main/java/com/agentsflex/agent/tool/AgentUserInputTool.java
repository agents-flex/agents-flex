/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建模型可见的用户输入请求工具。
 *
 * <p>该工具没有业务函数。模型调用后，AgentRunner 会保存原 ToolCall 并进入等待用户输入状态。
 * 用户提交的数据作为匹配该 ToolCall 的 ToolMessage 返回模型，由模型决定下一步业务动作。</p>
 */
public final class AgentUserInputTool {

    /**
     * 模型协议使用的稳定工具名。
     */
    public static final String NAME = "request_user_input";
    /**
     * 用于区分同名普通业务工具的内部能力标识。
     */
    public static final String CAPABILITY = "user-input";
    private static final String FORMS_METADATA = "agent.userInputForms";

    /**
     * 工厂类不持有实例状态，禁止构造。
     */
    private AgentUserInputTool() {
    }

    /**
     * 创建至少注册一个业务表单的工具构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 判断工具是否是由本类创建的受控用户输入工具。
     */
    public static boolean isUserInputTool(Tool tool) {
        return tool != null && NAME.equals(tool.getName())
            && CAPABILITY.equals(tool.getMetadata().get("agent.capability"));
    }

    /**
     * 解析并校验模型生成的表单请求参数。
     *
     * @return 与原 ToolCall 隔离的只读参数
     */
    public static AgentFormDefinition resolveForm(Tool tool, ToolCall call) {
        if (!isUserInputTool(tool)) {
            throw new IllegalArgumentException("AgentUserInputTool is required");
        }
        if (call == null || !NAME.equals(call.getName())) {
            throw new IllegalArgumentException("request_user_input ToolCall is required");
        }
        Map<String, Object> arguments = call.getArgsMap();
        String formKey = arguments == null ? null : stringValue(arguments.get("formKey"));
        if (formKey == null || formKey.trim().isEmpty()) {
            throw new IllegalArgumentException("request_user_input formKey must not be blank");
        }
        AgentFormDefinition form = forms(tool).get(formKey);
        if (form == null) {
            throw new IllegalArgumentException("request_user_input formKey is not allowed: " + formKey);
        }
        return form;
    }

    /**
     * 从受控工具元数据读取已注册表单集合。
     *
     * @return 表单标识到定义的映射；元数据不合法时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, AgentFormDefinition> forms(Tool tool) {
        Object value = tool.getMetadata().get(FORMS_METADATA);
        return value instanceof Map
            ? (Map<String, AgentFormDefinition>) value : Collections.emptyMap();
    }

    /**
     * 将可选工具参数转换为字符串，空值保持为 {@code null}。
     */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 用户输入工具构建器。
     */
    public static final class Builder {
        private final Map<String, AgentFormDefinition> forms = new LinkedHashMap<>();

        /**
         * 注册模型可以选择的一种表单。
         */
        public Builder form(AgentFormDefinition definition) {
            if (definition == null) return this;
            if (forms.put(definition.getFormKey(), definition) != null) {
                throw new IllegalArgumentException(
                    "duplicate user input formKey: " + definition.getFormKey());
            }
            return this;
        }

        /**
         * 创建只允许选择已注册 formKey 的内置控制工具。
         */
        public Tool build() {
            if (forms.isEmpty()) {
                throw new IllegalStateException("at least one user input form is required");
            }
            StringBuilder description = new StringBuilder(
                "当完成任务缺少必要信息时，从以下业务表单中选择一项请求用户填写；不要猜测缺失值：");
            for (AgentFormDefinition form : forms.values()) {
                description.append('[').append(form.getFormKey()).append("：")
                    .append(form.getDescription()).append("] ");
            }
            Map<String, AgentFormDefinition> definitions = Collections.unmodifiableMap(
                new LinkedHashMap<>(forms));
            return Tool.builder(NAME, description.toString())
                .addParameter(Parameter.builder().name("formKey").type("string")
                    .description("从已注册业务表单中选择一个稳定标识")
                    .enums(forms.keySet().toArray(new String[0]))
                    .required(true).build())
                .metadata("agent.internal", true)
                .metadata("agent.capability", CAPABILITY)
                .metadata(FORMS_METADATA, definitions)
                .function(arguments -> null)
                .build();
        }
    }
}
