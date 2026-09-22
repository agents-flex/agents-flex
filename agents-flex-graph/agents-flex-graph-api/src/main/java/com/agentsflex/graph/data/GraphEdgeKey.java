package com.agentsflex.graph.data;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.util.Objects;

/**
 * 可移植的边身份，由起点、边类型、终点和 rank 共同组成。
 */
public final class GraphEdgeKey {
    /**
     * 起点节点标识。
     */
    private final String sourceId;
    /**
     * 边类型。
     */
    private final String type;
    /**
     * 终点节点标识。
     */
    private final String targetId;
    /**
     * 同一端点和类型下用于区分平行边的序号。
     */
    private final long rank;

    /**
     * 创建边身份并校验可移植标识。
     */
    public GraphEdgeKey(String sourceId, String type, String targetId, long rank) {
        this.sourceId = GraphIdentifiers.requireText(sourceId, "source id");
        this.type = GraphIdentifiers.requireValid(type, "edge type");
        this.targetId = GraphIdentifiers.requireText(targetId, "target id");
        this.rank = rank;
    }

    /**
     * @return 起点标识
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * @return 边类型
     */
    public String getType() {
        return type;
    }

    /**
     * @return 终点标识
     */
    public String getTargetId() {
        return targetId;
    }

    /**
     * @return 平行边序号
     */
    public long getRank() {
        return rank;
    }

    /**
     * @return 用于跨适配器比较和日志记录的稳定字符串标识
     */
    public String portableId() {
        return sourceId + "\u001f" + type + "\u001f" + targetId + "\u001f" + rank;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GraphEdgeKey)) return false;
        GraphEdgeKey that = (GraphEdgeKey) other;
        return rank == that.rank && sourceId.equals(that.sourceId)
            && type.equals(that.type) && targetId.equals(that.targetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, type, targetId, rank);
    }
}
