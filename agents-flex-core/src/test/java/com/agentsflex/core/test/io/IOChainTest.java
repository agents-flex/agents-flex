package com.agentsflex.core.test.io;

import com.agentsflex.chain.IOChain;

public class IOChainTest {

    public static void main(String[] args) {

        IOChain ioChain1 = new IOChain();
        ioChain1.addNode(new IOAgent1("agent1"));
        ioChain1.addNode(new IOAgent2("agent2"));

        IOChain ioChain2 = new IOChain();
        ioChain2.addNode(new IOAgent1("agent3"));
        ioChain2.addNode(new IOAgent2("agent4"));
        ioChain2.addNode(ioChain1);


        Object result = ioChain2.execute("your params");
        System.out.println(result);
    }
}
