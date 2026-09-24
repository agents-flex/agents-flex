package com.agentsflex.graph;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.GraphPageRequest;
import com.agentsflex.graph.query.TraversalQuery;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 使用固定种子生成多组输入，验证查询对象和分页结果的不变量。
 *
 * <p>这不是依赖随机环境的模糊测试：每次运行都使用同一组输入，失败后可以稳定复现，
 * 同时覆盖比单个示例更宽的 limit、offset 和结果集规模组合。</p>
 */
public class GraphApiPropertyTest {
    @Test
    public void traversalQueriesShouldRemainValidAcrossGeneratedPageShapes() {
        for (int i = 0; i < 100; i++) {
            int offset = (i * 17) % 500;
            int limit = (i * 13) % 100 + 1;
            TraversalQuery query = TraversalQuery.from(
                    TraversalQuery.NodePattern.node("n", "Person"))
                .where(com.agentsflex.graph.query.GraphFilter.eq("n", "bucket", i % 7))
                .build().page(GraphPageRequest.of(offset, limit));

            query.validate();
            assertEquals(offset, query.getSkip());
            assertEquals(limit, query.getLimit());
        }
    }

    @Test
    public void pageMaterializationShouldNeverExposeMoreRecordsThanRequested() {
        for (int total = 0; total <= 40; total++) {
            List<GraphRecord> records = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                records.add(new GraphRecord(Collections.<String, Object>singletonMap("id", i)));
            }
            GraphResult result = new GraphResult(records, "MATCH",
                new GraphResultMetadata(total, false, 0L));
            for (int limit = 1; limit <= 10; limit++) {
                GraphResult page = result.forPage(limit, total > limit, total > limit ? "offset:" + limit : "");
                assertTrue(page.getRecords().size() <= limit);
                assertEquals(page.getRecords().size(), page.getMetadata().getRecordCount());
                assertEquals(total > limit, page.getMetadata().isTruncated());
            }
        }
    }

    /**
     * 使用固定种子生成节点、边和过滤查询，验证实体身份、rank 与查询校验在不同输入组合下保持稳定。
     */
    @Test
    public void generatedGraphEntitiesAndFiltersShouldPreserveCoreInvariants() {
        Random random = new Random(20260923L);
        for (int i = 0; i < 200; i++) {
            String fromId = "node-" + random.nextInt(50);
            String toId = "node-" + random.nextInt(50);
            int rank = random.nextInt(11) - 5;
            long score = random.nextInt(10_000);

            GraphNode node = GraphNode.builder(fromId, "Person")
                .property("score", score).build();
            GraphEdge edge = GraphEdge.builder(fromId, "RELATED_TO", toId)
                .rank(rank).property("score", score).build();
            GraphEdgeKey expectedKey = new GraphEdgeKey(fromId, "RELATED_TO", toId, rank);

            assertEquals(fromId, node.getId());
            assertEquals(expectedKey, edge.getKey());
            assertEquals(expectedKey.hashCode(), edge.getKey().hashCode());

            GraphFilter filter = i % 2 == 0
                ? GraphFilter.between("n", "score", Math.max(0L, score - 10L), score + 10L)
                : GraphFilter.and(GraphFilter.ge("n", "score", score),
                    GraphFilter.not(GraphFilter.isNull("n", "score")));
            TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Person"))
                .where(filter)
                .select(TraversalQuery.Projection.entity("n"))
                .orderBy(new TraversalQuery.Sort("n", "score", i % 3 == 0
                    ? TraversalQuery.SortDirection.DESC : TraversalQuery.SortDirection.ASC))
                .build();

            query.validate();
            assertEquals(filter, query.getFilter());
        }
    }
}
