/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.tool.Parameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可供搜索 Provider 存储和检索的 Tool 参数元数据。
 *
 * <p>该类型与执行过程无关，只保留参数名称、类型、描述、必填标记和嵌套参数结构。
 * 使用独立类型可以避免外部 Provider 必须序列化框架内部的 {@link Parameter} 对象。</p>
 */
public class ToolParameterInfo {
    /**
     * 参数名称。
     */
    private String name;
    /**
     * 参数类型，例如 {@code string}、{@code integer} 或 {@code object}。
     */
    private String type;
    /**
     * 参数的自然语言说明，可参与默认内存搜索。
     */
    private String description;
    /**
     * 参数是否必填。
     */
    private boolean required;
    /**
     * 对象、数组等复合参数的子参数定义。
     */
    private List<ToolParameterInfo> children = Collections.emptyList();

    /**
     * 将框架参数定义递归转换为可检索元数据。
     *
     * @param parameter 要转换的参数定义，不能为 {@code null}
     * @return 与原参数结构对应的元数据
     */
    public static ToolParameterInfo from(Parameter parameter) {
        ToolParameterInfo info = new ToolParameterInfo();
        info.name = parameter.getName();
        info.type = parameter.getType();
        info.description = parameter.getDescription();
        info.required = parameter.isRequired();
        List<Parameter> sourceChildren = parameter.getChildren();
        if (sourceChildren != null) {
            List<ToolParameterInfo> children = new ArrayList<>(sourceChildren.size());
            for (Parameter child : sourceChildren) {
                children.add(from(child));
            }
            info.children = children;
        }
        return info;
    }

    /**
     * @return 参数名称
     */
    public String getName() {
        return name;
    }

    /**
     * @param name 参数名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return 参数类型
     */
    public String getType() {
        return type;
    }

    /**
     * @param type 参数类型
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * @return 参数说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * @param description 参数说明
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return 参数是否必填
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * @param required 参数是否必填
     */
    public void setRequired(boolean required) {
        this.required = required;
    }

    /**
     * @return 不可修改的子参数列表
     */
    public List<ToolParameterInfo> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * 设置子参数定义。传入内容会被浅复制。
     *
     * @param children 子参数；传入 {@code null} 表示空列表
     */
    public void setChildren(List<ToolParameterInfo> children) {
        this.children = children == null ? Collections.emptyList() : new ArrayList<>(children);
    }
}
