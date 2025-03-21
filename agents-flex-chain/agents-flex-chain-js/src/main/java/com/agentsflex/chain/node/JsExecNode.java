package com.agentsflex.chain.node;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.node.CodeNode;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Map;

public class JsExecNode extends CodeNode {

    @Override
    protected Map<String, Object> executeCode(String code, Chain chain) {
        // 创建脚本引擎
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");

        if (engine == null) {
            throw new RuntimeException("未找到GraalJS引擎，请确认依赖配置");
        }

        // 配置引擎参数（通过Bindings）
        Bindings bindings = engine.createBindings();
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);

        // 获取并注入参数
        Map<String, Object> parameterValues = getParameterValues(chain);
        if (parameterValues != null) {
            bindings.putAll(parameterValues);
        }

        // 创建结果容器并注入上下文
        Map<String, Object> result = new HashMap<>();
        bindings.put("_chain", chain);
        bindings.put("_result", result);

        try {
            // 执行JavaScript代码
            engine.eval(code, bindings);
        } catch (ScriptException e) {
            throw new RuntimeException("GraalJS 执行失败", e);
        }

        return result;
    }

    @Override
    public String toString() {
        return "JsExecNode{" +
            "inwardEdges=" + inwardEdges +
            ", code='" + code + '\'' +
            ", description='" + description + '\'' +
            ", parameters=" + parameters +
            ", outputDefs=" + outputDefs +
            ", id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", async=" + async +
            ", outwardEdges=" + outwardEdges +
            ", condition=" + condition +
            ", memory=" + memory +
            ", nodeStatus=" + nodeStatus +
            '}';
    }

}
