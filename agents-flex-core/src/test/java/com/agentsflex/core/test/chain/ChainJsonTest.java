package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.Chain;
import org.junit.Test;

public class ChainJsonTest {

    @Test
    public void testChainToJson() {

        Chain chain1 = new Chain();

        TestNode node1 = new TestNode();
        TestNode node2 = new TestNode();
        TestNode node3 = new TestNode();

        node1.setId("node1");
        node1.setName("Node 1");
        node1.setDescription("This is node 1");

        node2.setId("node2");
        node2.setName("Node 2");
        node2.setDescription("This is node 2");

        node3.setId("node3");
        node3.setName("Node 3");
        node3.setDescription("This is node 3");

        chain1.addNode(node1);
        chain1.addNode(node2);
        chain1.addNode(node3);

        chain1.addEdge(node1.getId(), node2.getId());
        chain1.addEdge(node2.getId(), node3.getId());

        String json1 = chain1.toJSON();
        System.out.println(json1);

        Chain chain2 = Chain.fromJSON(json1);
        String json2 = chain2.toJSON();

        System.out.println(json2);

        assert json1.equals(json2);
    }
}
