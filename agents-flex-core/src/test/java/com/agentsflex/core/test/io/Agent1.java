package com.agentsflex.core.test.io;

import com.agentsflex.core.agent.DefaultAgent;
import com.agentsflex.core.chain.Chain;

public class Agent1 extends DefaultAgent {
    public Agent1(Object id) {
        super(id);
    }

    @Override
    public Object execute(Object parameter, Chain chain) {
        return "001:" + parameter;
    }
}
