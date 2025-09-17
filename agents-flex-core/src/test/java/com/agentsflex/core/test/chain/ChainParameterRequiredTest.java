package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.ChainSuspendException;
import com.agentsflex.core.chain.Parameter;
import com.agentsflex.core.chain.RefType;
import com.agentsflex.core.chain.listener.ChainSuspendListener;
import com.agentsflex.core.chain.node.BaseNode;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChainParameterRequiredTest {

    @Test
    public void test() {

        Chain chain = new Chain();

        BaseNode a = new BaseNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                return  chain.getParameterValues(this);
            }
        };
        a.setId("a");

        Parameter p = new Parameter();
        p.setName("image");
        p.setRefType(RefType.INPUT);
//        p.setRequired(true);
        a.addInputParameter(p);

        chain.addNode(a);



        chain.addSuspendListener(new ChainSuspendListener() {
            @Override
            public void onSuspend(Chain chain) {
                System.out.println("suspend!!!");
            }
        });

        try {
            System.out.println(chain.executeForResult(new HashMap<>()));
        } catch (ChainSuspendException e) {
            List<Parameter> suspendForParameters = chain.getSuspendForParameters();
            System.out.println("suspendForParameters:" + suspendForParameters);

            String json = chain.toJSON();
            System.out.println("json:" + json);
            Chain newChain = Chain.fromJSON(json);

            Map<String, Object> data = new HashMap<>();
            data.put("image", "https://picsum.photos/200/300");
            newChain.resume(data);
            System.out.println("result:: " + newChain.getMemory().getAll());
        }

    }
}
