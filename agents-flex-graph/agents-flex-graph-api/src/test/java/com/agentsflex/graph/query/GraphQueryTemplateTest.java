package com.agentsflex.graph.query;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证公共查询模板的预解析、参数绑定和并发复用契约。
 */
public class GraphQueryTemplateTest {
    @Test
    public void shouldParseTemplateWithoutValuesAndBindDifferentRequests() {
        GraphQueryTemplate template = GraphQueryParser.parseTemplate(
            "MATCH (p:Person) WHERE p.age >= :minAge AND p.status IN :statuses RETURN p");

        assertEquals(Arrays.asList("minAge", "statuses"),
            Arrays.asList(template.getParameterNames().toArray(new String[0])));
        TraversalQuery first = template.bind(new java.util.LinkedHashMap<String, Object>() {{
            put("minAge", 18L);
            put("statuses", Arrays.asList("ACTIVE"));
        }});
        TraversalQuery second = template.bind(new java.util.LinkedHashMap<String, Object>() {{
            put("minAge", 30L);
            put("statuses", Arrays.asList("DISABLED", "TRIAL"));
        }});

        assertEquals(18L, first.getFilter().getChildren().get(0).getValue());
        assertEquals(30L, second.getFilter().getChildren().get(0).getValue());
        assertEquals(Arrays.asList("ACTIVE"), first.getFilter().getChildren().get(1).getValue());
        assertEquals(Arrays.asList("DISABLED", "TRIAL"), second.getFilter().getChildren().get(1).getValue());
    }

    @Test
    public void boundValuesMustBeCopiedAndReadOnly() {
        GraphQueryTemplate template = GraphQueryParser.parseTemplate(
            "MATCH (p:Person) WHERE p.status IN :statuses RETURN p");
        java.util.List<String> statuses = new java.util.ArrayList<>(Arrays.asList("ACTIVE", "TRIAL"));
        TraversalQuery query = template.bind(Collections.<String, Object>singletonMap("statuses", statuses));
        statuses.clear();

        assertEquals(Arrays.asList("ACTIVE", "TRIAL"), query.getFilter().getValue());
        try {
            ((java.util.List<?>) query.getFilter().getValue()).clear();
            fail("bound collection must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 绑定查询不应持有调用方可变集合。
        }
    }

    @Test
    public void shouldRejectMissingTemplateParameter() {
        GraphQueryTemplate template = GraphQueryParser.parseTemplate(
            "MATCH (p:Person) WHERE p.age >= :minAge RETURN p");
        try {
            template.bind(Collections.<String, Object>emptyMap());
            fail("missing template parameter must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("minAge"));
        }
    }

    @Test
    public void patternPropertyParametersMustBeCollectedAndBound() {
        GraphQueryTemplate template = GraphQueryParser.parseTemplate(
            "MATCH (p:Person {status: :status})-[r:KNOWS {since: :year}]->(f:Person) RETURN p");
        assertEquals(Arrays.asList("status", "year"),
            Arrays.asList(template.getParameterNames().toArray(new String[0])));
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("status", "ACTIVE");
        values.put("year", 2020L);
        TraversalQuery query = template.bind(values);
        assertEquals("ACTIVE", query.getStart().getProperties().get("status"));
        assertEquals(2020L, query.getSteps().get(0).getEdge().getProperties().get("since"));
    }
}
