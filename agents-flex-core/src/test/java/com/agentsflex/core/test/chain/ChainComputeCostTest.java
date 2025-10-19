package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.*;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ChainComputeCostTest {

    public static class User {
       private int age;
       private String name;

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

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
        a.setComputeCostExpr("1");

        chain.addNode(a);

        ChainNode b = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                setNodeStatus(ChainNodeStatus.FINISHED_NORMAL);
                return Maps.of();
            }
        };
        b.setId("b");
        b.setComputeCostExpr("2");
        chain.addNode(b);

        ChainNode c = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                setNodeStatus(ChainNodeStatus.FINISHED_NORMAL);
                return Maps.of();
            }
        };
        c.setId("c");
        c.setComputeCostExpr("3");
        chain.addNode(c);

        ChainNode d = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("聚合");

                User user = new User();
                user.setAge(10);
                user.setName("张三");

                return Maps.of("user", user);
            }
        };
        d.setId("d");
        d.setComputeCostExpr("{{5 * user.getAge() }}");
        d.setCondition(new NodeCondition() {
            @Override
            public boolean check(Chain chain, NodeContext context, Map<String, Object> executeResult) {
                System.out.println("check!!!");
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

        System.out.println("chain.getComputeCost: " + chain.getComputeCost());

    }
}
