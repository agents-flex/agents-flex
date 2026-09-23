package com.agentsflex.graph.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;


/**
 * 单项后端能力及其面向用户的限制说明。
 */
public final class GraphCapabilityDetail {
    /**
     * 能力枚举项。
     */
    private final GraphFeature feature;
    /**
     * 当前后端是否支持该能力。
     */
    private final boolean supported;
    /**
     * 面向调用方的限制或替代方案说明。
     */
    private final String note;
    /**
     * 能力的结构化限制，例如最大跳数。
     */
    private final Map<String, String> limits;
    /**
     * 能力支持的运行模式，例如在线批量导入。
     */
    private final List<String> modes;

    /**
     * 创建没有结构化限制和模式的能力详情。
     */
    GraphCapabilityDetail(GraphFeature feature, boolean supported, String note) {
        this(feature, supported, note, Collections.<String, String>emptyMap(), Collections.<String>emptyList());
    }

    /**
     * 创建并冻结能力详情。
     */
    GraphCapabilityDetail(GraphFeature feature, boolean supported, String note,
                          Map<String, String> limits, List<String> modes) {
        this.feature = feature;
        this.supported = supported;
        this.note = note == null ? "" : note;
        this.limits = Collections.unmodifiableMap(new LinkedHashMap<>(limits == null
            ? Collections.<String, String>emptyMap() : limits));
        this.modes = Collections.unmodifiableList(new ArrayList<>(modes == null
            ? Collections.<String>emptyList() : modes));
    }

    /**
     * @return 能力类型
     */
    public GraphFeature getFeature() {
        return feature;
    }

    /**
     * @return 后端是否支持该能力
     */
    public boolean isSupported() {
        return supported;
    }

    /**
     * @return 限制、替代方案或实现说明
     */
    public String getNote() {
        return note;
    }

    /**
     * @return 后端能力的参数化限制，例如最大跳数或最大批次。
     */
    public Map<String, String> getLimits() {
        return limits;
    }

    /**
     * @return 后端支持的模式名称。
     */
    public List<String> getModes() {
        return modes;
    }
}
