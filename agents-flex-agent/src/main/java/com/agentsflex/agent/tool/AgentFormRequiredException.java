/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

/**
 * 业务工具在产生副作用前请求用户填写表单的控制流异常。
 *
 * <p>AgentRunner 会单独识别该异常：保留当前 ToolCall、保存表单定义并暂停 AgentTurn，而不会把它
 * 当作工具失败。用户提交后，Runner 从头重新执行原工具，工具通过
 * {@link AgentToolContext#getSubmittedFormData()} 读取提交内容。</p>
 *
 * <p>该异常必须在工具产生任何外部副作用之前抛出。Java 调用栈不会跨暂停点保存，恢复语义始终是
 * 重新执行整个工具函数，因此工具仍应使用 {@link AgentToolContext#getIdempotencyKey()} 保证幂等。</p>
 */
public final class AgentFormRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AgentFormDefinition form;

    public AgentFormRequiredException(AgentFormDefinition form) {
        super(message(form));
        if (form == null) {
            throw new IllegalArgumentException("form must not be null");
        }
        this.form = form;
    }

    /** @return 当前工具请求展示的不可变表单定义 */
    public AgentFormDefinition getForm() {
        return form;
    }

    private static String message(AgentFormDefinition form) {
        if (form == null) return "Tool requires user input";
        Object title = form.getSchema().get("title");
        return title == null ? form.getFormKey() : String.valueOf(title);
    }
}
