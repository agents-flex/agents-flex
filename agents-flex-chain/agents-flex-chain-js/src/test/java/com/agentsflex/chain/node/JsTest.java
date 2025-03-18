package com.agentsflex.chain.node;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Map;

public class JsTest {

    public static void main(String[] args) throws ScriptException {
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
}
