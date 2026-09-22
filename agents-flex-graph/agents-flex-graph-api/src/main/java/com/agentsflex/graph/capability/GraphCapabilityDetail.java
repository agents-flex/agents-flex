package com.agentsflex.graph.capability;


/**
 * 单项后端能力及其面向用户的限制说明。
 */
public final class GraphCapabilityDetail {
    private final GraphFeature feature;
    private final boolean supported;
    private final String note;

    GraphCapabilityDetail(GraphFeature feature, boolean supported, String note) {
        this.feature = feature;
        this.supported = supported;
        this.note = note == null ? "" : note;
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
}
