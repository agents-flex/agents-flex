/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ToolSearchResult {
    private ToolInfo toolInfo;
    private double score;
    private List<String> matchedFields = Collections.emptyList();

    public ToolSearchResult() {}
    public ToolSearchResult(ToolInfo toolInfo, double score, List<String> matchedFields) {
        this.toolInfo = toolInfo;
        this.score = score;
        setMatchedFields(matchedFields);
    }
    public ToolInfo getToolInfo() { return toolInfo; }
    public void setToolInfo(ToolInfo toolInfo) { this.toolInfo = toolInfo; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public List<String> getMatchedFields() { return Collections.unmodifiableList(matchedFields); }
    public void setMatchedFields(List<String> matchedFields) {
        this.matchedFields = matchedFields == null ? Collections.emptyList() : new ArrayList<>(matchedFields);
    }
}
