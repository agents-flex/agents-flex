package com.agentsflex.graph.query;

/**
 * 可移植的单段 OPTIONAL MATCH 查询，内部复用已校验的线性遍历 AST。
 */
public final class GraphOptionalQuery implements GraphQuery {
    private final TraversalQuery query;

    private GraphOptionalQuery(TraversalQuery query) {
        if (query == null) throw new IllegalArgumentException("optional query must not be null");
        this.query = query;
    }

    /**
     * 包装一个遍历查询为 OPTIONAL MATCH。
     */
    public static GraphOptionalQuery of(TraversalQuery query) {
        return new GraphOptionalQuery(query);
    }

    /**
     * @return 被包装的遍历查询。
     */
    public TraversalQuery getQuery() {
        return query;
    }

    @Override
    public void validate() {
        query.validate();
    }
}
