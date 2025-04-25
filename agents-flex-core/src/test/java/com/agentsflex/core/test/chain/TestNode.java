package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.node.BaseNode;

import java.util.Map;

public class TestNode extends BaseNode {

    @Override
    protected Map<String, Object> execute(Chain chain) {
        System.out.println("TestNode.execute: " + this.id);
        Map<String, Object> parameterValues = chain.getParameterValues(this);
        System.out.println("TestNode.values: " + parameterValues);
        return parameterValues;
    }
}
