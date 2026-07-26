/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import java.util.Collection;
import java.util.List;

/** Storage and search SPI for tool metadata. */
public interface ToolSearchProvider {
    void save(ToolInfo toolInfo);
    default void saveAll(Collection<ToolInfo> toolInfos) {
        if (toolInfos != null) for (ToolInfo toolInfo : toolInfos) save(toolInfo);
    }
    ToolInfo findByName(String name);
    List<ToolInfo> findAll();
    List<ToolSearchResult> search(ToolSearchRequest request);
    boolean remove(String name);
    void clear();
}
