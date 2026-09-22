package com.agentsflex.graph.capability;

import com.agentsflex.graph.capability.GraphCapabilityDetail;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.UnsupportedGraphFeatureException;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 图数据库适配器的不可变能力声明。
 */
public final class GraphCapabilities {
    /**
     * 当前适配器支持的能力，创建后不可修改。
     */
    private final Set<GraphFeature> features;
    /**
     * 能力限制及替代方案说明。
     */
    private final Map<GraphFeature, String> notes;

    private GraphCapabilities(Set<GraphFeature> features) {
        this(features, Collections.<GraphFeature, String>emptyMap());
    }

    private GraphCapabilities(Set<GraphFeature> features, Map<GraphFeature, String> notes) {
        this.features = Collections.unmodifiableSet(EnumSet.copyOf(features));
        EnumMap<GraphFeature, String> copy = new EnumMap<>(GraphFeature.class);
        copy.putAll(notes);
        this.notes = Collections.unmodifiableMap(copy);
    }

    /**
     * 创建包含指定能力的声明。
     *
     * @param first 至少声明一个能力，不能为 {@code null}
     * @param rest  其余能力，可以为空
     * @return 不可变能力声明
     */
    public static GraphCapabilities of(GraphFeature first, GraphFeature... rest) {
        EnumSet<GraphFeature> features = EnumSet.of(first);
        features.addAll(Arrays.asList(rest));
        return new GraphCapabilities(features);
    }

    /**
     * @return 不支持任何可选能力的空声明
     */
    public static GraphCapabilities none() {
        return new GraphCapabilities(EnumSet.noneOf(GraphFeature.class));
    }

    /**
     * @param feature 待检查的能力 @return 后端是否支持该能力
     */
    public boolean supports(GraphFeature feature) {
        return features.contains(feature);
    }

    /**
     * @return 只读的能力集合
     */
    public Set<GraphFeature> asSet() {
        return features;
    }

    /**
     * 为支持或不支持的能力增加限制说明，并返回新的不可变声明。
     *
     * <p>不支持的能力也可以添加说明，便于 UI 展示替代方案。</p>
     */
    public GraphCapabilities withNote(GraphFeature feature, String note) {
        if (feature == null) throw new IllegalArgumentException("feature must not be null");
        if (note == null || note.trim().isEmpty()) throw new IllegalArgumentException("note must not be blank");
        EnumMap<GraphFeature, String> copy = new EnumMap<>(GraphFeature.class);
        copy.putAll(notes);
        copy.put(feature, note);
        return new GraphCapabilities(features, copy);
    }

    /**
     * @return 指定能力的支持状态和限制说明
     */
    public GraphCapabilityDetail describe(GraphFeature feature) {
        if (feature == null) throw new IllegalArgumentException("feature must not be null");
        return new GraphCapabilityDetail(feature, supports(feature), notes.get(feature));
    }

    /**
     * @return 所有能力的完整矩阵，包括不支持项
     */
    public Map<GraphFeature, GraphCapabilityDetail> matrix() {
        EnumMap<GraphFeature, GraphCapabilityDetail> result = new EnumMap<>(GraphFeature.class);
        for (GraphFeature feature : GraphFeature.values()) result.put(feature, describe(feature));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 要求后端支持指定能力。
     *
     * @throws UnsupportedGraphFeatureException 后端不支持时抛出
     */
    public void require(GraphFeature feature) {
        if (!supports(feature)) {
            throw new UnsupportedGraphFeatureException("Graph backend does not support " + feature);
        }
    }
}
