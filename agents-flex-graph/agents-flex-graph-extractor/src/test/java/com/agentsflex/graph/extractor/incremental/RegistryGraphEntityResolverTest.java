package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.resolution.GraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolution;
import com.agentsflex.graph.extractor.resolution.GraphRegisteredEntity;
import com.agentsflex.graph.extractor.resolution.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * 跨批次实体注册和解析的行为测试。
 */
public class RegistryGraphEntityResolverTest {
    /**
     * 后续批次使用历史别名时必须复用已注册节点 ID 和主数据属性。
     */
    @Test
    public void shouldReuseRegisteredNodeIdByAliasAcrossBatches() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        registry.saveAll(Collections.singletonList(new GraphRegisteredEntity("person-001", "Character", "林默",
            Collections.singletonList("林公子"), props("age", 30L))));
        GraphEntityCandidate aliasMention = entity("c2::m1", "林公子", Collections.<String>emptyList(),
            props("age", 18L));

        GraphEntityResolution resolution = new RegistryGraphEntityResolver(registry)
            .resolve(Collections.singletonList(aliasMention));

        assertEquals("person-001", resolution.nodeId("c2::m1"));
        assertEquals(30L, resolution.getNodes().get(0).getProperties().get("age"));
    }

    /**
     * 一个候选同时命中多个历史节点时必须失败，不能静默污染长期知识库。
     */
    @Test
    public void shouldRejectAmbiguousMatchesFromPersistentRegistry() {
        GraphRegisteredEntity first = new GraphRegisteredEntity("person-001", "Character", "林默",
            Collections.<String>emptyList(), Collections.<String, Object>emptyMap());
        GraphRegisteredEntity second = new GraphRegisteredEntity("person-002", "Character", "黑衣人",
            Collections.<String>emptyList(), Collections.<String, Object>emptyMap());
        GraphEntityRegistry ambiguous = new GraphEntityRegistry() {
            @Override
            public List<GraphRegisteredEntity> find(String type, java.util.Collection<String> names) {
                return Arrays.asList(first, second);
            }

            @Override
            public void saveAll(java.util.Collection<GraphRegisteredEntity> entities) {
                throw new UnsupportedOperationException();
            }
        };
        GraphEntityCandidate bridge = entity("c3::m1", "林默", Collections.singletonList("黑衣人"),
            props("name", "林默"));

        try {
            new RegistryGraphEntityResolver(ambiguous).resolve(Collections.singletonList(bridge));
            fail("ambiguous registry matches should be rejected");
        } catch (GraphExtractionException expected) {
            assertEquals(true, expected.getMessage().contains("Ambiguous registered entities"));
        }
    }

    /**
     * 注册表不得把同类型同名称悄悄改绑到另一个节点。
     */
    @Test
    public void shouldRejectRegistryNameReassignment() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        GraphRegisteredEntity first = new GraphRegisteredEntity("person-001", "Character", "林默",
            Collections.<String>emptyList(), Collections.<String, Object>emptyMap());
        GraphRegisteredEntity conflicting = new GraphRegisteredEntity("person-002", "Character", "林默",
            Collections.<String>emptyList(), Collections.<String, Object>emptyMap());
        registry.saveAll(Collections.singletonList(first));

        try {
            registry.saveAll(Collections.singletonList(conflicting));
            fail("name reassignment should be rejected");
        } catch (GraphExtractionException expected) {
            assertSame(first, registry.find("Character", Collections.singletonList("林默")).get(0));
        }
    }

    /**
     * 创建人物候选。
     */
    private static GraphEntityCandidate entity(String key, String name, List<String> aliases,
                                               Map<String, Object> properties) {
        return new GraphEntityCandidate(key, name, "Character", aliases, properties,
            new GraphEvidence("novel", "chunk", name, -1, -1, null), 1D);
    }

    /**
     * 创建单属性有序映射。
     */
    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
