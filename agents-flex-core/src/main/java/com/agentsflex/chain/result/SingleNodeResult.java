package com.agentsflex.chain.result;

import com.agentsflex.chain.NodeResult;

public class SingleNodeResult implements NodeResult<Object> {

    private Object value;

    public SingleNodeResult(Object value) {
        this.value = value;
    }

    @Override
    public Object getValue() {
        return null;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
