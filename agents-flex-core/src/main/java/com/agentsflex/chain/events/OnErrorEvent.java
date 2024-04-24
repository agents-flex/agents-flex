package com.agentsflex.chain.events;

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainEvent;

public class OnErrorEvent implements ChainEvent {

    private Chain<?,?> chain;
    private Exception exception;

    public OnErrorEvent(Chain<?, ?> chain, Exception exception) {
        this.chain = chain;
        this.exception = exception;
    }

    @Override
    public String name() {
        return "error";
    }

    public Chain<?, ?> getChain() {
        return chain;
    }

    public void setChain(Chain<?, ?> chain) {
        this.chain = chain;
    }

    public Exception getException() {
        return exception;
    }

    public void setException(Exception exception) {
        this.exception = exception;
    }
}
