package com.agentsflex.core.test.io;

import com.agentsflex.agent.IOAgent;
import com.agentsflex.chain.Chain;

public class IOAgent2 extends IOAgent {

    public IOAgent2(Object id) {
        super(id);
    }
    @Override
    public Object execute(Object param, Chain chain) {
        return "002:" + param;
    }
}
