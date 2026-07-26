/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

public class ToolSearchRequest {
    public static final int DEFAULT_MAX_RESULTS = 5;
    private String query;
    private int maxResults = DEFAULT_MAX_RESULTS;
    private String category;

    public ToolSearchRequest() {}
    public ToolSearchRequest(String query) { this.query = query; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) {
        if (maxResults <= 0) throw new IllegalArgumentException("maxResults must be greater than zero");
        this.maxResults = maxResults;
    }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
