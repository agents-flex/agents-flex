package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.mapping.GraphCandidateMutationMapper;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolutionResult;
import com.agentsflex.graph.extractor.resolution.NameAliasGraphEntityResolver;
import com.agentsflex.graph.mutation.GraphMutation;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 默认实体归一和 GraphMutation 映射测试。
 */
public class GraphEntityResolverAndMapperTest {
    /**
     * 同类型名称命中前一实体别名时应合并为同一个稳定节点。
     */
    @Test
    public void shouldMergeAliasesAcrossChunksAndKeepTypesSeparate() {
        GraphEntityCandidate first = entity("c1::m1", "林默", "Character",
            Collections.singletonList("林公子"), props("name", "林默"));
        GraphEntityCandidate alias = entity("c2::m1", "林公子", "Character",
            Collections.emptyList(), props("age", 18L));
        GraphEntityCandidate placeWithSameName = entity("c3::m1", "林默", "Organization",
            Collections.emptyList(), props("name", "林默"));

        GraphEntityResolutionResult resolution = new NameAliasGraphEntityResolver().resolve(
            Arrays.asList(first, alias, placeWithSameName));

        assertEquals(2, resolution.getNodes().size());
        assertEquals(resolution.nodeId(first.getCandidateKey()), resolution.nodeId(alias.getCandidateKey()));
        assertNotEquals(resolution.nodeId(first.getCandidateKey()), resolution.nodeId(placeWithSameName.getCandidateKey()));
        assertEquals("林默", resolution.getNodes().get(0).getProperties().get("name"));
        assertEquals(18L, resolution.getNodes().get(0).getProperties().get("age"));
    }

    /**
     * 相同端点、类型和 rank 的重叠关系应只生成一条边。
     */
    @Test
    public void mapperShouldDeduplicateOverlapRelations() {
        GraphEntityCandidate person = entity("c::p", "林默", "Character", Collections.emptyList(), props("name", "林默"));
        GraphEntityCandidate organization = entity("c::o", "青云宗", "Organization", Collections.emptyList(), props("name", "青云宗"));
        GraphEntityResolutionResult resolution = new NameAliasGraphEntityResolver().resolve(Arrays.asList(person, organization));
        GraphRelationCandidate relation = new GraphRelationCandidate("c::p", "MEMBER_OF", "c::o", 0L,
            props("chapter", 1L), GraphExtractorTestSupport.evidence("c", "加入青云宗"), 1D, GraphAssertionType.EXPLICIT);

        GraphMutation mutation = new GraphCandidateMutationMapper().map(resolution, Arrays.asList(relation, relation));

        assertEquals(2, mutation.getNodes().size());
        assertEquals(1, mutation.getEdges().size());
        assertEquals(resolution.nodeId("c::p"), mutation.getEdges().get(0).getSourceId());
    }

    /**
     * 同键重叠关系属性冲突时应选择更高置信度候选，置信度相同则保持首次结果。
     */
    @Test
    public void mapperShouldPreferHigherConfidenceDuplicateRelation() {
        GraphEntityCandidate person = entity("c::p", "林默", "Character", Collections.emptyList(), props("name", "林默"));
        GraphEntityCandidate organization = entity("c::o", "青云宗", "Organization", Collections.emptyList(), props("name", "青云宗"));
        GraphEntityResolutionResult resolution = new NameAliasGraphEntityResolver().resolve(Arrays.asList(person, organization));
        GraphRelationCandidate low = new GraphRelationCandidate("c::p", "MEMBER_OF", "c::o", 0L,
            props("chapter", 1L), GraphExtractorTestSupport.evidence("c", "加入"), 0.7D,
            GraphAssertionType.EXPLICIT);
        GraphRelationCandidate high = new GraphRelationCandidate("c::p", "MEMBER_OF", "c::o", 0L,
            props("chapter", 2L), GraphExtractorTestSupport.evidence("c", "加入"), 0.9D,
            GraphAssertionType.EXPLICIT);

        GraphMutation mutation = new GraphCandidateMutationMapper().map(resolution, Arrays.asList(low, high));

        assertEquals(1, mutation.getEdges().size());
        assertEquals(2L, mutation.getEdges().get(0).getProperties().get("chapter"));
    }

    /**
     * 相同类型和名称应始终生成相同节点 ID。
     */
    @Test
    public void generatedIdsShouldBeDeterministic() {
        GraphEntityCandidate candidate = entity("c::p", "林默", "Character", Collections.emptyList(), props("name", "林默"));
        String first = new NameAliasGraphEntityResolver().resolve(Collections.singletonList(candidate)).nodeId("c::p");
        String second = new NameAliasGraphEntityResolver().resolve(Collections.singletonList(candidate)).nodeId("c::p");
        assertEquals(first, second);
        assertTrue(first.startsWith("character:"));
    }

    /**
     * 新候选同时命中两个旧别名集合时应执行传递合并。
     */
    @Test
    public void shouldMergeTwoExistingGroupsThroughAliasBridge() {
        GraphEntityCandidate first = entity("c1::a", "林默", "Character", Collections.emptyList(), props("name", "林默"));
        GraphEntityCandidate second = entity("c2::b", "黑衣人", "Character", Collections.emptyList(), props("name", "黑衣人"));
        GraphEntityCandidate bridge = entity("c3::c", "林默", "Character", Collections.singletonList("黑衣人"), props("age", 18L));

        GraphEntityResolutionResult resolution = new NameAliasGraphEntityResolver().resolve(Arrays.asList(first, second, bridge));

        assertEquals(1, resolution.getNodes().size());
        assertEquals(resolution.nodeId("c1::a"), resolution.nodeId("c2::b"));
    }

    /**
     * 常见代词即使被模型误放入 aliases，也不能成为跨实体合并键。
     */
    @Test
    public void pronounsShouldNotMergeUnrelatedCharacters() {
        GraphEntityCandidate first = entity("c1::a", "林默", "Character", Collections.singletonList("他"), props("name", "林默"));
        GraphEntityCandidate second = entity("c2::b", "赵无极", "Character", Collections.singletonList("他"), props("name", "赵无极"));
        assertEquals(2, new NameAliasGraphEntityResolver().resolve(Arrays.asList(first, second)).getNodes().size());
    }

    private static GraphEntityCandidate entity(String key, String name, String type, java.util.List<String> aliases,
                                               Map<String, Object> properties) {
        return new GraphEntityCandidate(key, name, type, aliases, properties,
            GraphExtractorTestSupport.evidence(key, name), 1D);
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
