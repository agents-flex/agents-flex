package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.*;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ChainAsyncStringTest {

    @Test
    public void test() {

        Chain chain = new Chain();

        TestNode a = new TestNode();
        a.setId("a");
        chain.addNode(a);

        TestNode b = new TestNode(){
            @Override
            protected Map<String, Object> execute(Chain chain) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("b");
                return Maps.of();
            }
        };
        b.setId("b");
        b.setAsync(true);

        chain.addNode(b);

        TestNode c = new TestNode();
        c.setId("c");
        chain.addNode(c);

        TestNode d = new TestNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("d: "+ Thread.currentThread().getId());
                return Maps.of();
            }
        };
        d.setId("d");

        d.setCondition(new JsCodeCondition("_context.isUpstreamFullyExecuted()"));
        chain.addNode(d);

        ChainEdge ab = new ChainEdge();
        ab.setSource("a");
        ab.setTarget("b");
        chain.addEdge(ab);

        ChainEdge ac = new ChainEdge();
        ac.setSource("a");
        ac.setTarget("c");
        chain.addEdge(ac);


        ChainEdge bd = new ChainEdge();
        bd.setSource("b");
        bd.setTarget("d");
        chain.addEdge(bd);

        ChainEdge cd = new ChainEdge();
        cd.setSource("c");
        cd.setTarget("d");
        chain.addEdge(cd);


        // A→B→D
        //  ↘C↗
        chain.executeForResult(new HashMap<>());

    }
}
