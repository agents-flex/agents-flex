/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.tool.Parameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Serializable, invocation-free parameter metadata used by tool search providers. */
public class ToolParameterInfo {
    private String name;
    private String type;
    private String description;
    private boolean required;
    private List<ToolParameterInfo> children = Collections.emptyList();

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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public List<ToolParameterInfo> getChildren() { return Collections.unmodifiableList(children); }
    public void setChildren(List<ToolParameterInfo> children) {
        this.children = children == null ? Collections.emptyList() : new ArrayList<>(children);
    }
}
