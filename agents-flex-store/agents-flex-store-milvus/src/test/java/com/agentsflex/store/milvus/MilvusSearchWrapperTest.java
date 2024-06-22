package com.agentsflex.store.milvus;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class MilvusSearchWrapperTest {

    @Test
    public void test01() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("akey", "avalue").eq(Connector.OR, "bkey", "bvalue").group(rw1 -> {
            rw1.eq("ckey", "avalue").in(Connector.AND_NOT, "dkey", Arrays.asList("aa", "bb"));
        }).eq("a", "b");

        String expr = "akey == \"avalue\" OR bkey == \"bvalue\" AND (ckey == \"avalue\" AND NOT dkey IN [\"aa\",\"bb\"]) AND a == \"b\"";
        Assert.assertEquals(expr, rw.toFilterExpression(MilvusExpressionAdaptor.DEFAULT));

        System.out.println(rw.toFilterExpression());
    }


    @Test
    public void test02() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("akey", "avalue").between(Connector.OR, "bkey", "1", "100").in("ckey", Arrays.asList("aa", "bb"));

        String expr = "akey == \"avalue\" OR (bkey >= 1 && bkey <= 100) AND ckey IN [\"aa\",\"bb\"]";
        Assert.assertEquals(expr, rw.toFilterExpression(MilvusExpressionAdaptor.DEFAULT));

        System.out.println(rw.toFilterExpression());
    }
}
