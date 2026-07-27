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

/**
 * Tool 的可检索元数据。
 *
 * <p>该对象只描述 Tool，不包含 Tool 的执行函数，因此可以交给数据库、全文索引、
 * Elasticsearch 等外部存储实现持久化。真正可执行的 {@link Tool} 仍保存在
 * {@link ToolSearchManager} 本地，搜索命中后再通过名称关联二者。</p>
 *
 * <p>{@link #from(Tool)} 会复制 Tool 自带的名称、描述和参数信息；分类、标签和业务
 * 扩展信息需要由开发者按需补充。</p>
 */
public class ToolInfo {
    /**
     * Tool 的唯一名称，同时也是元数据与可执行 Tool 之间的关联键。
     */
    private String name;
    /**
     * Tool 的能力描述，是默认内存检索的重要匹配字段。
     */
    private String description;
    /**
     * 可选的业务分类，用于搜索请求中的精确分类过滤。
     */
    private String category;
    /**
     * 可选的检索标签，可补充描述中没有出现的同义词或业务关键词。
     */
    private List<String> tags = Collections.emptyList();
    /**
     * 不包含执行逻辑的参数结构，用于按参数名称和描述检索 Tool。
     */
    private List<ToolParameterInfo> parameters = Collections.emptyList();
    /**
     * 供自定义 Provider 使用的业务扩展属性，默认内存 Provider 不参与评分。
     */
    private Map<String, Object> metadata = Collections.emptyMap();

    /**
     * 从可执行 Tool 创建基础检索元数据。
     *
     * <p>该方法复制名称、描述及完整参数树，不复制执行回调。返回对象仍可通过
     * Setter 补充分类、标签和自定义属性。</p>
     *
     * @param tool 要提取元数据的可执行 Tool
     * @return 独立的 Tool 元数据对象
     * @throws IllegalArgumentException 当 tool 为 {@code null} 时抛出
     */
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

    /**
     * @return Tool 名称
     */
    public String getName() {
        return name;
    }

    /**
     * @param name Tool 名称，应与对应可执行 Tool 的名称一致
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return Tool 的能力描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * @param description Tool 的能力描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return Tool 的业务分类，可以为 {@code null}
     */
    public String getCategory() {
        return category;
    }

    /**
     * @param category Tool 的业务分类
     */
    public void setCategory(String category) {
        this.category = category;
    }

    /**
     * @return 不可修改的标签列表
     */
    public List<String> getTags() {
        return Collections.unmodifiableList(tags);
    }

    /**
     * 设置检索标签。传入内容会被复制，后续修改原列表不会影响该对象。
     *
     * @param tags 检索标签；传入 {@code null} 表示空列表
     */
    public void setTags(List<String> tags) {
        this.tags = tags == null ? Collections.emptyList() : new ArrayList<>(tags);
    }

    /**
     * @return 不可修改的参数元数据列表
     */
    public List<ToolParameterInfo> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    /**
     * 设置参数元数据。传入内容会被浅复制，避免外部增删原列表改变内部状态。
     *
     * @param parameters 参数元数据；传入 {@code null} 表示空列表
     */
    public void setParameters(List<ToolParameterInfo> parameters) {
        this.parameters = parameters == null ? Collections.emptyList() : new ArrayList<>(parameters);
    }

    /**
     * @return 不可修改的业务扩展属性视图
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * 设置业务扩展属性。属性由自定义 Provider 自行解释。
     *
     * @param metadata 扩展属性；传入 {@code null} 表示空 Map
     */
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Collections.emptyMap() : new LinkedHashMap<>(metadata);
    }
}
