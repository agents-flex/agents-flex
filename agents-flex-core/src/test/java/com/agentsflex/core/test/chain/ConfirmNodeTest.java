package com.agentsflex.core.test.chain;

import com.agentsflex.core.chain.*;
import com.agentsflex.core.chain.listener.ChainSuspendListener;
import com.agentsflex.core.chain.node.ConfirmNode;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfirmNodeTest {

    @Test
    public void test() {

        Chain chain = new Chain();

        ChainNode a = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("a-->>execute");
                return Maps.of();
            }
        };
        a.setId("a");
        chain.addNode(a);

        ChainNode b = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("b-->>execute");

                Map<String, Object> image1 = new HashMap<>();
                image1.put("src", "https://image1");

                Map<String, Object> image2 = new HashMap<>();
                image2.put("src", "https://image2");

                List<Map<String, Object>> images = new ArrayList<>();
                images.add(image1);
                images.add(image2);


                return Maps.of("images", images);
            }
        };
        b.setId("b");
        chain.addNode(b);


        ConfirmNode c = new ConfirmNode();
        c.setMessage("请确认 xx 是否正确？");
        c.setId("c");

        ConfirmNode.ConfirmParameter p = new ConfirmNode.ConfirmParameter();
        p.setName("image");
        p.setRef("b.images.src");
        p.setRequired(true);

        List<ConfirmNode.ConfirmParameter> confirms = new ArrayList<>();
        confirms.add(p);
        c.setConfirms(confirms);

        chain.addNode(c);

        ChainNode d = new ChainNode() {
            @Override
            protected Map<String, Object> execute(Chain chain) {
                System.out.println("d-->>execute");
                return Maps.of();
            }
        };
        d.setId("d");
        chain.addNode(d);

        ChainEdge ab = new ChainEdge();
        ab.setSource("a");
        ab.setTarget("b");
        chain.addEdge(ab);

        ChainEdge bc = new ChainEdge();
        bc.setSource("b");
        bc.setTarget("c");
        chain.addEdge(bc);

        ChainEdge cd = new ChainEdge();
        cd.setSource("c");
        cd.setTarget("d");
        chain.addEdge(cd);


        chain.addSuspendListener(new ChainSuspendListener() {
            @Override
            public void onSuspend(Chain chain) {
                System.out.println("suspend!!!");
            }
        });

        try {
            // A→B→C（ConfirmNode）→D
            chain.executeForResult(new HashMap<>());
        } catch (ChainSuspendException e) {
            List<Parameter> suspendForParameters = chain.getSuspendForParameters();
            System.out.println("suspendForParameters:" + suspendForParameters);


            String json = chain.toJSON();
            Chain newChain = Chain.fromJSON(json);

            Map<String, Object> data = new HashMap<>();
            for (Parameter parameter : suspendForParameters) {
                if (parameter instanceof ConfirmNode.ConfirmParameter) {
                    data.put(parameter.getName(), ((ConfirmNode.ConfirmParameter) parameter).getSelectionData().get(0));
                }
            }
            newChain.resume(data);
            System.out.println("result:: " + newChain.getMemory().getAll());
        }

    }
}
