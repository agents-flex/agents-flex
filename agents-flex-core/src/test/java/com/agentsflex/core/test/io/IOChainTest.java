package com.agentsflex.core.test.io;

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainEvent;
import com.agentsflex.chain.ChainEventListener;
import com.agentsflex.chain.IOChain;

public class IOChainTest {

    public static void main(String[] args) {

        IOChain ioChain1 = new IOChain();
        ioChain1.addNode(new IOAgent1("11"));
        ioChain1.addNode(new IOAgent2("22"));

        IOChain ioChain2 = new IOChain();
        ioChain2.addNode(new IOAgent1("33"));
        ioChain2.addNode(new IOAgent2("44"));
        ioChain2.addNode(ioChain1);

        ioChain2.registerEventListener(new ChainEventListener() {
            @Override
            public void onEvent(ChainEvent event, Chain chain) {
                System.out.println(chain.getId()+" --->>>" +event);
            }
        });
        Object result = ioChain2.execute("aaa");
        System.out.println(result);
    }
}
