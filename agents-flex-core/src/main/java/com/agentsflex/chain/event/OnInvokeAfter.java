package com.agentsflex.chain.event;

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainEvent;
import com.agentsflex.chain.ChainNode;

public class OnInvokeAfter implements ChainEvent {

    private Chain<?,?> chain;
    private ChainNode chainNode;
    private Object result;

    public OnInvokeAfter(Chain<?, ?> chain, ChainNode chainNode, Object result) {
        this.chain = chain;
        this.chainNode = chainNode;
        this.result = result;
    }

    @Override
    public String name() {
        return "invokeAfter";
    }

    public Chain<?, ?> getChain() {
        return chain;
    }

    public void setChain(Chain<?, ?> chain) {
        this.chain = chain;
    }

    public ChainNode getInvoker() {
        return chainNode;
    }

    public void setInvoker(ChainNode chainNode) {
        this.chainNode = chainNode;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }
}
