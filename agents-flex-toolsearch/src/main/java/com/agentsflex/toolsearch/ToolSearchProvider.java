/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import java.util.Collection;
import java.util.List;

/**
 * Tool 元数据存储与检索的扩展接口。
 *
 * <p>Provider 只处理 {@link ToolInfo}，不持有 Tool 的执行回调。默认实现为内存中的
 * 加权词法搜索；工具数量很大或需要集中存储、全文检索、语义检索时，可以实现该接口
 * 接入数据库、Lucene、Elasticsearch 或向量检索服务。</p>
 */
public interface ToolSearchProvider {
    /**
     * 新增或更新一条 Tool 元数据。
     *
     * @param toolInfo 要保存的元数据
     */
    void save(ToolInfo toolInfo);

    /**
     * 批量保存 Tool 元数据。默认实现逐条调用 {@link #save(ToolInfo)}。
     *
     * @param toolInfos 要保存的元数据集合；可以为 {@code null}
     */
    default void saveAll(Collection<ToolInfo> toolInfos) {
        if (toolInfos != null) for (ToolInfo toolInfo : toolInfos) save(toolInfo);
    }

    /**
     * @param name Tool 名称
     * @return 同名元数据；不存在时返回 {@code null}
     */
    ToolInfo findByName(String name);

    /**
     * @return 当前 Provider 中的全部 Tool 元数据
     */
    List<ToolInfo> findAll();

    /**
     * 按请求条件搜索 Tool。
     *
     * @param request 查询文本、最大结果数和可选分类
     * @return 搜索结果，建议按相关度从高到低排列
     */
    List<ToolSearchResult> search(ToolSearchRequest request);

    /**
     * @param name 要删除的 Tool 名称
     * @return 是否实际删除了元数据
     */
    boolean remove(String name);

    /**
     * 清空 Provider 中的全部 Tool 元数据。
     */
    void clear();
}
