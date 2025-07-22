package com.agentsflex.chain.node;

import com.agentsflex.core.chain.Chain;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class CodeNodeTest {

    @Test
    public void test() {

        String code = "const arr = []\n" +
                "const obj = {\n" +
                "\"name\":\"workflow\",\n" +
                "\"age\":\"21\",\n" +
                "\"is_member\":\"true\",\n" +
                "\"join_time\":\"1753085883652\",\n" +
                "\"join_money\":\"500.00\"\n" +
                "}\n" +
                "arr.push(obj)\n" +
                "_result.arr = arr";

        Chain chain = new Chain();

        JsExecNode a = new JsExecNode();
        a.setId("a");
        a.setCode(code);
        chain.addNode(a);

        Map<String, Object> result = chain.executeForResult(new HashMap<>());
        System.out.println(result.getClass());
        System.out.println(result);
    }
}
