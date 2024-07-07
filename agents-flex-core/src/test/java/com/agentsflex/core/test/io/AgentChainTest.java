package com.agentsflex.core.test.io;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.ChainEvent;
import com.agentsflex.core.chain.ChainEventListener;
import com.agentsflex.core.chain.ChainException;
import com.agentsflex.core.chain.impl.LoopChain;
import com.agentsflex.core.chain.impl.SequentialChain;
import org.junit.Test;

public class AgentChainTest {

    @Test
    public void testSequential() throws ChainException {

        SequentialChain chain1 = new SequentialChain();
        chain1.addNode(new Agent1("agent1"));
        chain1.addNode(new Agent2("agent2"));

        SequentialChain chain2 = new SequentialChain();
        chain2.addNode(new Agent1("agent3"));
        chain2.addNode(new Agent2("agent4"));


        chain1.addNode(chain2);

        chain1.registerEventListener(new ChainEventListener() {
            @Override
            public void onEvent(ChainEvent event, Chain chain) {
                System.out.println(event);
            }
        });


        Object result = chain1.executeForResult("your params");
        System.out.println(">>>>>" + result);
    }


    @Test
    public void testLoop() throws ChainException {

        LoopChain chain1 = new LoopChain();
        chain1.addNode(new Agent1("agent1"));
        chain1.addNode(new Agent2("agent2"));

        chain1.close();
        chain1.setMaxLoopCount(10);

        Object result = chain1.executeForResult("your params");
        System.out.println(">>>>>" + result);
    }
}
