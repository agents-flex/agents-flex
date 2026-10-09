package com.agentsflex.graph.extractor.resolution;

import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.registry.GraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolutionResult;
import com.agentsflex.graph.extractor.registry.GraphRegisteredEntity;
import com.agentsflex.graph.extractor.registry.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
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
        registry.saveAll("knowledge", Collections.singletonList(new GraphRegisteredEntity("person-001", "Character", "林默",
            Collections.singletonList("林公子"), props("age", 30L))));
        GraphEntityCandidate aliasMention = entity("c2::m1", "林公子", Collections.<String>emptyList(),
            props("age", 18L));

        GraphEntityResolutionResult resolution = new RegistryGraphEntityResolver("knowledge", registry)
            .resolve(Collections.singletonList(aliasMention));

        assertEquals("person-001", resolution.findNodeId("c2::m1"));
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
            public List<GraphRegisteredEntity> findMatches(String space, String type, java.util.Collection<String> names) {
                return Arrays.asList(first, second);
            }

            @Override
            public void saveAll(String space, java.util.Collection<GraphRegisteredEntity> entities) {
                throw new UnsupportedOperationException();
            }
        };
        GraphEntityCandidate bridge = entity("c3::m1", "林默", Collections.singletonList("黑衣人"),
            props("name", "林默"));

        try {
            new RegistryGraphEntityResolver("knowledge", ambiguous).resolve(Collections.singletonList(bridge));
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
        registry.saveAll("knowledge", Collections.singletonList(first));

        try {
            registry.saveAll("knowledge", Collections.singletonList(conflicting));
            fail("name reassignment should be rejected");
        } catch (GraphExtractionException expected) {
            assertSame(first, registry.findMatches("knowledge", "Character", Collections.singletonList("林默")).get(0));
        }
    }

    /**
     * 使用显式 Space 作用域时，不同知识库中的同名实体不得互相命中。
     */
    @Test
    public void shouldIsolateRegisteredEntitiesBySpace() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        registry.saveAll("space-a", Collections.singletonList(new GraphRegisteredEntity("person-a", "Character",
            "林默", Collections.<String>emptyList(), Collections.<String, Object>emptyMap())));

        GraphEntityCandidate mention = entity("c1::m1", "林默", Collections.<String>emptyList(),
            props("name", "林默"));
        GraphEntityResolutionResult inA = new RegistryGraphEntityResolver("space-a", registry)
            .resolve(Collections.singletonList(mention));
        GraphEntityResolutionResult inB = new RegistryGraphEntityResolver("space-b", registry)
            .resolve(Collections.singletonList(mention));

        assertEquals("person-a", inA.findNodeId("c1::m1"));
        assertFalse("person-a".equals(inB.findNodeId("c1::m1")));
        assertEquals(0, registry.findMatches("space-b", "Character", Collections.singletonList("林默")).size());
    }

    /** 所有持久身份查询和写入必须限定 Space，不接受隐式的全局注册表。 */
    @Test
    public void shouldRequireExplicitSpaceForRegistryAndResolver() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        for (String space : Arrays.asList(null, "", "  ")) {
            assertThrows(IllegalArgumentException.class,
                () -> registry.findMatches(space, "Character", Collections.singletonList("林默")));
            assertThrows(IllegalArgumentException.class,
                () -> registry.saveAll(space, Collections.emptyList()));
            assertThrows(IllegalArgumentException.class,
                () -> new RegistryGraphEntityResolver(space, registry));
        }
        assertThrows(IllegalArgumentException.class,
            () -> registry.findMatches("knowledge", "", Collections.singletonList("林默")));
    }

    /** 批次后部的别名冲突不能留下前部新实体或属性修改。 */
    @Test
    public void shouldRollbackWholeRegistrationBatchOnNameConflict() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        GraphRegisteredEntity original = new GraphRegisteredEntity("p1", "Character", "林默",
            Collections.singletonList("林公子"), props("age", 30L));
        registry.saveAll("knowledge", Collections.singletonList(original));
        GraphRegisteredEntity update = new GraphRegisteredEntity("p1", "Character", "林默",
            Collections.singletonList("掌门"), props("age", 31L));
        GraphRegisteredEntity newEntity = new GraphRegisteredEntity("p2", "Character", "苏青",
            Collections.emptyList(), Collections.emptyMap());
        GraphRegisteredEntity conflict = new GraphRegisteredEntity("p3", "Character", "黑衣人",
            Collections.singletonList("林公子"), Collections.emptyMap());
        assertThrows(GraphExtractionException.class,
            () -> registry.saveAll("knowledge", Arrays.asList(update, newEntity, conflict)));
        assertEquals(1, registry.size());
        assertSame(original, registry.findMatches("knowledge", "Character", Collections.singletonList("林默")).get(0));
        assertTrue(registry.findMatches("knowledge", "Character", Arrays.asList("掌门", "苏青")).isEmpty());
    }

    /** 同一身份重复注册合并别名与属性，并保持 Space 和类型隔离。 */
    @Test
    public void shouldMergeIdentityAndIsolateTypeAndSpace() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        registry.saveAll("knowledge", Collections.singletonList(new GraphRegisteredEntity("p1", "Character", "林默",
            Collections.singletonList("林公子"), props("age", 30L))));
        GraphRegisteredEntity update = new GraphRegisteredEntity("p1", "Character", "林默",
            Collections.singletonList("掌门"), props("title", "宗主"));
        registry.saveAll("knowledge", Arrays.asList(update, update));
        assertEquals(1, registry.size());
        List<GraphRegisteredEntity> matches = registry.findMatches("knowledge", "Character",
            Arrays.asList("林公子", "掌门"));
        assertEquals(1, matches.size());
        assertEquals(30L, matches.get(0).getProperties().get("age"));
        assertEquals("宗主", matches.get(0).getProperties().get("title"));
        assertTrue(registry.findMatches("other", "Character", Collections.singletonList("掌门")).isEmpty());
        assertTrue(registry.findMatches("knowledge", "Organization", Collections.singletonList("林默")).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> matches.clear());
    }

    /** 创建人物候选。 */
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
