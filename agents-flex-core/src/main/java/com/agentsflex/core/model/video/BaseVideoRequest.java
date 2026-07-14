/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 视频请求公共参数。
 * <p>
 * 提供请求级模型覆盖和服务商扩展参数。请求级模型优先于 Config 中配置的默认模型。
 */
public class BaseVideoRequest {
    /**
     * 本次请求使用的模型名称。为空时由具体实现使用 Config 中的默认模型。
     */
    private String model;

    /**
     * 服务商或模型特有的扩展参数。
     * <p>
     * 适配器负责约定参数放置位置。例如阿里云适配器支持以 {@code input}、
     * {@code parameters} 和 {@code topLevel} 为键传入嵌套 Map。
     */
    private Map<String, Object> options;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Map<String, Object> getOptions() {
        return options == null ? Collections.emptyMap() : Collections.unmodifiableMap(options);
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options == null ? null : new HashMap<>(options);
    }

    /**
     * 添加或覆盖一个服务商扩展参数。
     *
     * @param key 参数名称
     * @param value 参数值
     */
    public void addOption(String key, Object value) {
        if (options == null) {
            options = new HashMap<>();
        }
        options.put(key, value);
    }

    /**
     * 获取指定服务商扩展参数。
     *
     * @param key 参数名称
     * @return 参数值，不存在时返回 {@code null}
     */
    public Object getOption(String key) {
        return options == null ? null : options.get(key);
    }
}
