package com.agentsflex.core.test.io;

import com.agentsflex.agent.DefaultAgent;
import com.agentsflex.chain.Chain;

public class Agent2 extends DefaultAgent {

    public Agent2(Object id) {
        super(id);
    }

    @Override
    public Object execute(Object parameter, Chain chain) {
        return "002:" + parameter;
    }
}
