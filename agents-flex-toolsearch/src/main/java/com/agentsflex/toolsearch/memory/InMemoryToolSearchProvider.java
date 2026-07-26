/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch.memory;

import com.agentsflex.toolsearch.ToolInfo;
import com.agentsflex.toolsearch.ToolParameterInfo;
import com.agentsflex.toolsearch.ToolSearchProvider;
import com.agentsflex.toolsearch.ToolSearchRequest;
import com.agentsflex.toolsearch.ToolSearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Dependency-free O(n) search for the common case of dozens or hundreds of tools. */
public class InMemoryToolSearchProvider implements ToolSearchProvider {
    private final ConcurrentMap<String, ToolInfo> tools = new ConcurrentHashMap<>();

    @Override
    public void save(ToolInfo toolInfo) {
        if (toolInfo == null || !hasText(toolInfo.getName())) {
            throw new IllegalArgumentException("ToolInfo and its name must not be blank");
        }
        tools.put(toolInfo.getName(), toolInfo);
    }

    @Override
    public ToolInfo findByName(String name) { return name == null ? null : tools.get(name); }

    @Override
    public List<ToolInfo> findAll() {
        List<ToolInfo> result = new ArrayList<>(tools.values());
        result.sort(Comparator.comparing(ToolInfo::getName));
        return result;
    }

    @Override
    public List<ToolSearchResult> search(ToolSearchRequest request) {
        if (request == null) throw new IllegalArgumentException("ToolSearchRequest must not be null");
        String query = normalize(request.getQuery());
        List<String> tokens = tokenize(request.getQuery());
        List<ToolSearchResult> results = new ArrayList<>();
        for (ToolInfo tool : tools.values()) {
            if (hasText(request.getCategory())
                && !normalize(request.getCategory()).equals(normalize(tool.getCategory()))) continue;
            Score score = score(tool, query, tokens);
            if (!hasText(query) || score.value > 0) {
                results.add(new ToolSearchResult(tool, score.value, new ArrayList<>(score.fields)));
            }
        }
        results.sort(Comparator.comparingDouble(ToolSearchResult::getScore).reversed()
            .thenComparing(result -> result.getToolInfo().getName()));
        return results.size() > request.getMaxResults()
            ? new ArrayList<>(results.subList(0, request.getMaxResults())) : results;
    }

    @Override
    public boolean remove(String name) { return name != null && tools.remove(name) != null; }

    @Override
    public void clear() { tools.clear(); }

    private static Score score(ToolInfo tool, String query, List<String> tokens) {
        Score score = new Score();
        String name = normalize(tool.getName());
        String description = normalize(tool.getDescription());
        String category = normalize(tool.getCategory());
        if (hasText(query) && name.equals(query)) score.add(120, "name");
        else if (hasText(query) && name.contains(query)) score.add(50, "name");
        if (hasText(query) && description.contains(query)) score.add(30, "description");

        int matchedTokens = 0;
        for (String token : tokens) {
            boolean matched = false;
            if (containsWord(name, token)) { score.add(25, "name"); matched = true; }
            else if (name.contains(token)) { score.add(12, "name"); matched = true; }
            if (description.contains(token)) { score.add(6, "description"); matched = true; }
            if (category.equals(token)) { score.add(20, "category"); matched = true; }
            else if (category.contains(token)) { score.add(8, "category"); matched = true; }
            for (String tag : tool.getTags()) {
                if (normalize(tag).contains(token)) { score.add(15, "tags"); matched = true; }
            }
            if (parametersContain(tool.getParameters(), token, score)) matched = true;
            if (matched) matchedTokens++;
        }
        if (!tokens.isEmpty()) score.value += 20.0 * matchedTokens / tokens.size();
        return score;
    }

    private static boolean parametersContain(List<ToolParameterInfo> parameters, String token, Score score) {
        boolean matched = false;
        for (ToolParameterInfo parameter : parameters) {
            if (normalize(parameter.getName()).contains(token)) { score.add(10, "parameters"); matched = true; }
            if (normalize(parameter.getDescription()).contains(token)) { score.add(4, "parameters"); matched = true; }
            if (parametersContain(parameter.getChildren(), token, score)) matched = true;
        }
        return matched;
    }

    private static boolean containsWord(String value, String token) {
        return Arrays.asList(value.split(" ")).contains(token);
    }

    private static List<String> tokenize(String value) {
        String normalized = normalize(value);
        if (!hasText(normalized)) return Collections.emptyList();
        return new ArrayList<>(new LinkedHashSet<>(Arrays.asList(normalized.split(" "))));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("([\\p{Ll}\\d])(\\p{Lu})", "$1 $2")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private static class Score {
        private double value;
        private final Set<String> fields = new LinkedHashSet<>();
        private void add(double points, String field) { value += points; fields.add(field); }
    }
}
