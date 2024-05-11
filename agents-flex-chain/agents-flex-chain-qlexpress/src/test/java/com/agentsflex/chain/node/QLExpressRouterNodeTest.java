package com.agentsflex.chain.node;

import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;

public class QLExpressRouterNodeTest {

    public static void main(String[] args) throws Exception {
        String express = "if(default.contains(\"b\")){return \"end\"} else {return \"next\"}";

        ExpressRunner runner = new ExpressRunner();
        DefaultContext<String, Object> context = new DefaultContext<>();
        context.put("default", "a");

        Object result = runner.execute(express, context, null, true, false);

        System.out.println(result);
    }
}
