package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.schema.GraphSchema;

import java.util.Collections;

/**
 * 测试共享的小说图谱 Schema 和证据工厂。
 */
final class GraphExtractorTestSupport {
    private GraphExtractorTestSupport() {
    }

    /**
     * 创建人物、组织以及隶属关系的最小 Schema。
     */
    static GraphSchema schema() {
        GraphSchema.Property name = new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true);
        return GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Character", name,
                new GraphSchema.Property("age", GraphSchema.PropertyType.INT64, false)))
            .nodeType(GraphSchema.NodeType.of("Organization", name))
            .edgeType(GraphSchema.EdgeType.of("MEMBER_OF", "Character", "Organization",
                new GraphSchema.Property("chapter", GraphSchema.PropertyType.INT64, false)))
            .build();
    }

    /**
     * 创建属于指定分段的原文证据。
     */
    static GraphEvidence evidence(String chunk, String quote) {
        return new GraphEvidence("novel-1", chunk, quote, -1, -1, Collections.<String, Object>emptyMap());
    }
}
