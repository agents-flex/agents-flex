package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.*;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ChainNodeLoopConditionTest {

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
                System.out.println("b>>> execute!");
                return Maps.of();
            }
        };
        b.setId("b");
        b.setLoopEnable(true);
//        b.setLoopBreakCondition(new NodeCondition() {
//            @Override
//            public boolean check(Chain chain, NodeContext context) {
//                int executeCount = context.getExecuteCount();
//                System.out.println("b loopBreak check >>>> " + executeCount);
//                return executeCount > 4;
//            }
//        });


        b.setLoopBreakCondition(new JsCodeCondition("_context.getExecuteCount() > 4"));


        chain.addNode(b);

        ChainNode c = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                 System.out.println("c>>> execute!");
                return Maps.of();
            }
        };
        c.setId("c");
        chain.addNode(c);

        ChainNode d = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("d>>> execute!");
                return Maps.of();
            }
        };
        d.setId("d");
        d.setCondition(new NodeCondition() {
            @Override
            public boolean check(Chain chain, NodeContext context) {
                System.out.println("d check >>>> " + context.isUpstreamFullyExecuted());
                return context.isUpstreamFullyExecuted();
            }
        });
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
