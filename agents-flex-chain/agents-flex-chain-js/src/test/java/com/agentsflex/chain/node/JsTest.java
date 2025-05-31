package com.agentsflex.chain.node;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JsTest {


    @Test
    public void testStatic() throws ScriptException {
        // 创建脚本引擎
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");

        if (engine == null) {
            throw new RuntimeException("未找到 GraalJS 引擎，请确认依赖配置");
        }

        // 配置引擎参数（通过Bindings）
        Bindings bindings = engine.createBindings();
        // 正确配置GraalJS参数（可能需通过Context配置，此处仅为示例）
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);

        // 获取并注入参数
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", 123);
        parameters.put("userName", "测试用户");
        bindings.putAll(parameters);

        // 创建结果容器并注入上下文
        Map<String, Object> result = new HashMap<>();
        bindings.put("_result", result);
        // 使用Map的put方法
        String jsCode =
            "_result.put('code', userName);\n" +
                "_result.put('data', '返回数据');\n";
        try {
            // 执行JavaScript代码
            engine.eval(jsCode, bindings);
        } catch (ScriptException e) {
            throw new RuntimeException("GraalJS执行失败", e);
        }

        System.err.println(result);
    }

    @Test
    public void testNode() throws InterruptedException {

        Person p = new Person("测试用户");

        JsExecNode chainNode = new JsExecNode();
        String jsCode =
            "_result.put('userName', user?.name);\n" +
                "_result.put('data', user?.p.greet());\n";
        chainNode.setCode(jsCode);

        Chain chain = new Chain();
        chain.addNode(chainNode);

        System.out.println(">>>>>execute before");
        Map<String, Object> result = chain.executeForResult(Maps.of("user", Maps.of("name", "测试用户").set("p",p)));
        System.out.println(">>>>>result:" + result);
    }


    @Test
    public void testNodeThread() throws InterruptedException {
        Person p = new Person("测试用户");
        for (int i = 0; i < 10; i++) {
            final int i1 = i;
            new Thread(() -> {
                JsExecNode chainNode = new JsExecNode();
                String jsCode =
                    "_result.put('userName', user?.name);\n" +
                        "_result.put('data', user?.p.greet());\n";
                chainNode.setCode(jsCode);

                Chain chain = new Chain();
                chain.addNode(chainNode);

                System.out.println(">>>>>execute before");
                Map<String, Object> result = chain.executeForResult(Maps.of("user", Maps.of("name", "测试用户" + i1).set("p",p)));
                System.out.println(">>>>>result:" + result);
            }).start();
        }
        TimeUnit.SECONDS.sleep(2);
    }



    public static class Person {
        public String name;
        public LocalDateTime birthDay;

        public Person(String name) {
            this.name = name;
            this.birthDay = LocalDateTime.now().minusYears(30);
        }

        public String greet() {
            return "Hello, I'm " + name;
        }
    }
}
