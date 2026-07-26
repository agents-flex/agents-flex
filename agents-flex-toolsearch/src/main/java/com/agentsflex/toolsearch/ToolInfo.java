/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool metadata that can be stored externally without serializing its executable callback. */
public class ToolInfo {
    private String name;
    private String description;
    private String category;
    private List<String> tags = Collections.emptyList();
    private List<ToolParameterInfo> parameters = Collections.emptyList();
    private Map<String, Object> metadata = Collections.emptyMap();

    public static ToolInfo from(Tool tool) {
        if (tool == null) throw new IllegalArgumentException("Tool must not be null");
        ToolInfo info = new ToolInfo();
        info.name = tool.getName();
        info.description = tool.getDescription();
        Parameter[] sourceParameters = tool.getParameters();
        if (sourceParameters != null) {
            List<ToolParameterInfo> parameters = new ArrayList<>(sourceParameters.length);
            for (Parameter parameter : sourceParameters) {
                if (parameter != null) parameters.add(ToolParameterInfo.from(parameter));
            }
            info.parameters = parameters;
        }
        return info;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public void setTags(List<String> tags) { this.tags = tags == null ? Collections.emptyList() : new ArrayList<>(tags); }
    public List<ToolParameterInfo> getParameters() { return Collections.unmodifiableList(parameters); }
    public void setParameters(List<ToolParameterInfo> parameters) {
        this.parameters = parameters == null ? Collections.emptyList() : new ArrayList<>(parameters);
    }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Collections.emptyMap() : new LinkedHashMap<>(metadata);
    }
}
