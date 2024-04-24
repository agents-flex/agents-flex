package com.agentsflex.chain.events;

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainEvent;
import com.agentsflex.chain.Invoker;

public class OnInvokeAfter implements ChainEvent {

    private Chain<?,?> chain;
    private Invoker invoker;
    private Object result;

    public OnInvokeAfter(Chain<?, ?> chain, Invoker invoker, Object result) {
        this.chain = chain;
        this.invoker = invoker;
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

    public Invoker getInvoker() {
        return invoker;
    }

    public void setInvoker(Invoker invoker) {
        this.invoker = invoker;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }
}
