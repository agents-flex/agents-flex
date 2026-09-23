package com.agentsflex.graph.importing;

/**
 * 导入恢复点，记录外部数据流中已经确认完成的节点、边和批次数量。
 *
 * <p>调用方应把 checkpoint 回调中的偏移持久化，并在重新创建相同顺序的数据源后传入该对象。</p>
 */
public final class GraphImportResumePoint {
    /**
     * 已成功确认的节点数量，用作下一次节点流的跳过偏移。
     */
    private final long nodesProcessed;
    /**
     * 已成功确认的边数量，用作下一次边流的跳过偏移。
     */
    private final long edgesProcessed;
    /**
     * 已处理批次序号，主要用于进度展示和批次编号延续。
     */
    private final int batchesProcessed;

    /**
     * 创建导入恢复点。
     *
     * @param nodesProcessed   已确认完成的节点数量
     * @param edgesProcessed   已确认完成的边数量
     * @param batchesProcessed 已处理批次序号
     * @throws IllegalArgumentException 任一偏移为负数时抛出
     */
    public GraphImportResumePoint(long nodesProcessed, long edgesProcessed, int batchesProcessed) {
        if (nodesProcessed < 0 || edgesProcessed < 0 || batchesProcessed < 0) {
            throw new IllegalArgumentException("import resume offsets must not be negative");
        }
        this.nodesProcessed = nodesProcessed;
        this.edgesProcessed = edgesProcessed;
        this.batchesProcessed = batchesProcessed;
    }

    /**
     * @return 从数据源起点开始处理的空恢复点。
     */
    public static GraphImportResumePoint beginning() {
        return new GraphImportResumePoint(0, 0, 0);
    }

    /**
     * @return 已确认完成的节点偏移。
     */
    public long getNodesProcessed() {
        return nodesProcessed;
    }

    /**
     * @return 已确认完成的边偏移。
     */
    public long getEdgesProcessed() {
        return edgesProcessed;
    }

    /**
     * @return 已处理批次序号。
     */
    public int getBatchesProcessed() {
        return batchesProcessed;
    }
}
