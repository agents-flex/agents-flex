package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.*;
import com.agentsflex.core.chain.listener.ChainSuspendListener;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ChainHolderTest {

    @Test
    public void test() {

        Chain chain = new Chain();

        TestNode a = new TestNode();
        a.setId("a");
        chain.addNode(a);

        TestNode b = new TestNode();
        b.setId("b");
        b.addInputParameter(new Parameter("p1", true));
        chain.addNode(b);

        TestNode c = new TestNode();
        c.setId("c");
        chain.addNode(c);

        ChainEdge ab = new ChainEdge();
        ab.setSource("a");
        ab.setTarget("b");
        chain.addEdge(ab);

        ChainEdge ac = new ChainEdge();
        ac.setSource("b");
        ac.setTarget("c");
        chain.addEdge(ac);

        final String[] holder = {null};
        chain.addSuspendListener(new ChainSuspendListener() {
            @Override
            public void onSuspend(Chain chain) {
                System.out.println("Suspend0");
                holder[0] = ChainHolder.fromChain(chain).toJSON();
            }
        });

        // A→B→C
        Map<String, Object> result = chain.executeForResult(new HashMap<>(),true);
        System.out.println(result);

        if (holder[0] != null){
            Chain chain1 = ChainHolder.fromJSON(holder[0]).toChain();
            chain1.addSuspendListener(new ChainSuspendListener() {
                @Override
                public void onSuspend(Chain chain) {
                    System.out.println("Suspend1");
                }
            });

            System.out.println(chain1.executeForResult(Maps.of(),true));
            System.out.println(chain1.executeForResult(Maps.of("p1","v1"),true));
        }
    }
}
