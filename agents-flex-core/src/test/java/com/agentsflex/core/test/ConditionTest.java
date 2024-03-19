package com.agentsflex.core.test;

import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.condition.Connector;
import org.junit.Assert;
import org.junit.Test;

public class ConditionTest {

    @Test
    public void test01() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("akey", "avalue").eq(Connector.OR, "bkey", "bvalue").group(rw1 -> {
            rw1.eq("ckey", "avalue").in(Connector.AND_NOT, "dkey", "bvalue");
        }).eq("a", "b");

        String expr = "akey = \"avalue\" OR bkey = \"bvalue\" AND (ckey = \"avalue\" AND NOT dkey IN \"bvalue\") AND a = \"b\"";
        Assert.assertEquals(expr, rw.toFilterExpression());

        System.out.println(rw.toFilterExpression());
    }
}
