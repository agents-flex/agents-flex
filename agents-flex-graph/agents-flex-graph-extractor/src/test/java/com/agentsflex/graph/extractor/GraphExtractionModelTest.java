package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolution;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * 抽取请求、选项和候选模型的边界与不可变性测试。
 */
public class GraphExtractionModelTest {
    /**
     * 默认值应对模型成本、上下文和事实质量提供保守限制。
     */
    @Test
    public void optionsShouldExposeConservativeDefaults() {
        GraphExtractionOptions options = GraphExtractionOptions.DEFAULT;
        assertEquals(0.6D, options.getMinConfidence(), 0D);
        assertEquals(1000, options.getContextCharacters());
        assertEquals(200, options.getMaxEntitiesPerChunk());
        assertEquals(true, options.isRequireEvidence());
        assertEquals(true, options.isFailOnChunkError());
    }

    /**
     * 证据元数据和候选属性必须复制输入，避免调用方后续修改快照。
     */
    @Test
    public void evidenceAndCandidateShouldCopyMutableInputs() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chapter", 1);
        GraphEvidence evidence = new GraphEvidence("d", "c", "林默", 0, 2, metadata);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", "林默");
        GraphEntityCandidate candidate = new GraphEntityCandidate("c::m1", "林默", "Character",
            Collections.emptyList(), properties, evidence, 1D);
        metadata.put("chapter", 2);
        properties.put("name", "changed");

        assertEquals(1, evidence.getMetadata().get("chapter"));
        assertEquals("林默", candidate.getProperties().get("name"));
    }

    /**
     * 请求元数据同样必须成为只读快照。
     */
    @Test(expected = UnsupportedOperationException.class)
    public void requestMetadataShouldBeImmutable() {
        GraphExtractionRequest request = GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c")
            .metadata(Collections.singletonMap("chapter", 1)).build();
        request.getMetadata().put("chapter", 2);
    }

    /**
     * 结束偏移不能小于起始偏移。
     */
    @Test(expected = IllegalArgumentException.class)
    public void evidenceShouldRejectInvalidOffsets() {
        new GraphEvidence("d", "c", "林默", 3, 2, Collections.emptyMap());
    }

    /**
     * 置信度不能超过 1。
     */
    @Test(expected = IllegalArgumentException.class)
    public void candidateShouldRejectInvalidConfidence() {
        new GraphEntityCandidate("c::m1", "林默", "Character", Collections.emptyList(),
            Collections.emptyMap(), null, 1.1D);
    }

    /**
     * 每段候选数量必须为正数。
     */
    @Test(expected = IllegalArgumentException.class)
    public void optionsShouldRejectZeroCandidateLimit() {
        GraphExtractionOptions.builder().maxEntitiesPerChunk(0);
    }

    /**
     * NaN 不属于 0 到 1 的有效置信度，不能利用浮点比较特性绕过边界检查。
     */
    @Test(expected = IllegalArgumentException.class)
    public void entityShouldRejectNanConfidence() {
        new GraphEntityCandidate("c::m1", "林默", "Character", Collections.emptyList(),
            Collections.emptyMap(), null, Double.NaN);
    }

    /**
     * 关系候选同样必须拒绝无穷置信度。
     */
    @Test(expected = IllegalArgumentException.class)
    public void relationShouldRejectInfiniteConfidence() {
        new GraphRelationCandidate("c::m1", "MEMBER_OF", "c::m2", 0L, Collections.emptyMap(),
            null, Double.POSITIVE_INFINITY, null);
    }

    /**
     * 最低置信度本身也必须是有限数，避免所有候选意外通过。
     */
    @Test(expected = IllegalArgumentException.class)
    public void optionsShouldRejectNanConfidenceThreshold() {
        GraphExtractionOptions.builder().minConfidence(Double.NaN);
    }

    /**
     * 未知证据偏移必须成对使用 -1，不能保存一半已知、一半未知的歧义状态。
     */
    @Test(expected = IllegalArgumentException.class)
    public void evidenceShouldRejectPartiallyUnknownOffsets() {
        new GraphEvidence("d", "c", "林默", -1, 2, Collections.emptyMap());
    }

    /**
     * 模型响应字符上限必须为正数。
     */
    @Test(expected = IllegalArgumentException.class)
    public void optionsShouldRejectZeroResponseLimit() {
        GraphExtractionOptions.builder().maxResponseCharacters(0);
    }

    /**
     * 候选批次应在边界处拒绝 null 元素，避免后续校验阶段出现无上下文空指针。
     */
    @Test(expected = IllegalArgumentException.class)
    public void candidateBatchShouldRejectNullElements() {
        new GraphCandidateBatch(Arrays.asList((GraphEntityCandidate) null), null, null, "{}");
    }

    /**
     * 实体归一结果必须提供完整的节点与候选映射集合。
     */
    @Test(expected = IllegalArgumentException.class)
    public void entityResolutionShouldRejectNullCollections() {
        new GraphEntityResolution(null, Collections.emptyMap());
    }
}
