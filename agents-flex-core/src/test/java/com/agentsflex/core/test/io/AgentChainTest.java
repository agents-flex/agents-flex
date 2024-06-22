package com.agentsflex.core.test.io;

import com.agentsflex.core.chain.*;

public class AgentChainTest {

    public static void main(String[] args) throws ChainException {

        SequentialChain ioChain1 = new SequentialChain();
        ioChain1.addNode(new Agent1("agent1"));
        ioChain1.addNode(new Agent2("agent2"));

        SequentialChain ioChain2 = new SequentialChain();
        ioChain2.addNode(new Agent1("agent3"));
        ioChain2.addNode(new Agent2("agent4"));
        ioChain2.addNode(ioChain1);

        ioChain2.registerEventListener(new ChainEventListener() {
            @Override
            public void onEvent(ChainEvent event, Chain chain) {
                System.out.println(event);
            }
        });


        Object result = ioChain2.executeForResult("your params");
        System.out.println(result);
    }
}
