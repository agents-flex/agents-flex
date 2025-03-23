package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.node.BaseNode;

import java.util.Collections;
import java.util.Map;

public class TestNode extends BaseNode {

    @Override
    protected Map<String, Object> execute(Chain chain) {
        Map<String, Object> parameterValues = getParameterValues(chain);
        return Collections.emptyMap();
    }
}
