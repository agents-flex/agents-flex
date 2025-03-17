package com.agentsflex.chain.node;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Map;

public class JsTest {

    public static void main(String[] args) throws ScriptException {

        // 创建脚本引擎管理器
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");
        // 创建绑定上下文
        Bindings bindings = engine.createBindings();
        bindings.put("userId", 123);
        bindings.put("userName", "测试用户");
        // 创建结果容器并注入上下文
        Map<String, Object> result = new HashMap<>();
        bindings.put("_result", result);
        String jsCode =
        "_result.code=1001;\n" +
            "_result.data='返回数据';\n";
        // 执行JavaScript代码
        engine.eval(jsCode, bindings);
        System.err.println(result);
    }
}
