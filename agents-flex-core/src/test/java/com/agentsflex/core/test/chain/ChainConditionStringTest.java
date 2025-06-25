package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.*;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ChainConditionStringTest {

    @Test
    public void test() {

        Chain chain = new Chain();

        ChainNode a = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                return Maps.of();
            }
        };
        a.setId("a");
        chain.addNode(a);

        ChainNode b = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                setNodeStatus(ChainNodeStatus.FINISHED_NORMAL);
                return Maps.of();
            }
        };
        b.setId("b");
        chain.addNode(b);

        ChainNode c = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                setNodeStatus(ChainNodeStatus.FINISHED_NORMAL);
                return Maps.of();
            }
        };
        c.setId("c");
        chain.addNode(c);

        ChainNode d = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("聚合");
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
