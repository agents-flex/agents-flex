package com.agentsflex.graph.query;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证 {@link GraphFilter} 对空值比较的拒绝契约。
 *
 * <p>Neo4j 的 {@code = null} 与 nGQL 的 {@code == NULL} 都只产生 null，WHERE 会把这类行全部
 * 滤掉且不报错。如果构造阶段放行，调用方拿到的是空结果而不是明确的用法错误，因此这里要求
 * 比较类运算符在收到 null 时立即失败，并指向 IS NULL / IS NOT NULL。</p>
 */
public class GraphFilterNullValueTest {
    @Test
    public void shouldRejectNullEqualityAndPointToIsNull() {
        try {
            GraphFilter.eq("person", "name", null);
            fail("equality against null can never match and must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("IS NULL"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("isNull"));
        }
    }

    @Test
    public void shouldRejectNullInequalityAndPointToIsNotNull() {
        try {
            GraphFilter.ne("person", "name", null);
            fail("inequality against null can never match and must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("IS NOT NULL"));
        }
    }

    @Test
    public void shouldRejectNullStringOperatorsAsWell() {
        assertRejected(new Runnable() {
            @Override
            public void run() {
                GraphFilter.contains("person", "bio", null);
            }
        });
        assertRejected(new Runnable() {
            @Override
            public void run() {
                GraphFilter.startsWith("person", "bio", null);
            }
        });
        assertRejected(new Runnable() {
            @Override
            public void run() {
                GraphFilter.regex("person", "bio", null);
            }
        });
    }

    @Test
    public void shouldStillBuildNullPredicates() {
        assertEquals(GraphFilter.Operator.IS_NULL, GraphFilter.isNull("person", "email").getOperator());
        assertEquals(GraphFilter.Operator.IS_NOT_NULL, GraphFilter.isNotNull("person", "email").getOperator());
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("null comparison value must be rejected");
        } catch (IllegalArgumentException expected) {
            // 期望构造阶段直接失败，而不是生成一个永远匹配不到任何内容的谓词。
        }
    }
}
