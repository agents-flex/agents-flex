package com.agentsflex.core.test;

import com.agentsflex.store.RetrieveWrapper;
import com.agentsflex.store.condition.Connector;
import org.junit.Test;

public class ConditionTest {

    @Test
    public void test01() {
        RetrieveWrapper rw = new RetrieveWrapper();
        rw.eq("akey", "avalue").eq(Connector.OR,"bkey", "bvalue").group(rw1 -> {
            rw1.eq("ckey", "avalue").eq("dkey", "bvalue");
        });

        System.out.println(rw.toExpression());
    }
}
