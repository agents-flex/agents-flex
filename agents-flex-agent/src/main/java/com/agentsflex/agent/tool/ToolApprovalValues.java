/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import com.alibaba.fastjson2.JSON;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批值对象共用的深复制和稳定指纹工具。
 *
 * <p>审批请求可能跨进程等待很长时间，因此不能仅冻结最外层 Map；嵌套 Map、集合和数组也必须
 * 与调用方隔离。指纹计算时还会递归排序 Map 键，避免同一业务数据因插入顺序不同而被误判为新请求。</p>
 */
public final class ToolApprovalValues {

    private ToolApprovalValues() {
    }

    /**
     * 递归复制并冻结字符串键 Map。
     */
    public static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            copy.put(entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 根据审批展示内容生成稳定 SHA-256 指纹。
     *
     * <p>调用方可显式提供业务版本作为指纹；未提供时使用 code、message、reason 和 metadata
     * 生成。Tool 恢复后必须重新构造当前决策并匹配此值，预检金额或版本变化便会自动重新审批。</p>
     */
    public static String fingerprint(String code, String message, String reason,
                                     Map<String, ?> metadata) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", code);
        request.put("message", message);
        request.put("reason", reason);
        request.put("metadata", canonicalValue(metadata));
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object immutableValue(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(entry.getKey(), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Collection<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(immutableValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(((Map<?, ?>) value).entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries) {
                sorted.put(String.valueOf(entry.getKey()), canonicalValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Collection) {
            List<Object> values = new ArrayList<>();
            for (Object item : (Collection<?>) value) values.add(canonicalValue(item));
            return values;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(canonicalValue(Array.get(value, index)));
            }
            return values;
        }
        return value;
    }
}
