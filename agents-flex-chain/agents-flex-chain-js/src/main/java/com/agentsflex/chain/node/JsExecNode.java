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
        // 创建脚本引擎管理器
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");
        // 创建绑定上下文
        Bindings bindings = engine.createBindings();
        // 获取并注入参数
        Map<String, Object> parameters = getParameters(chain);
        if (parameters != null) {
            bindings.putAll(parameters);
        }
        // 创建结果容器并注入上下文
        Map<String, Object> result = new HashMap<>();
        bindings.put("_chain", chain);
        bindings.put("_result", result);

        try {
            // 执行JavaScript代码
            engine.eval(code, bindings);
        } catch (ScriptException e) {
            throw new RuntimeException("JavaScript执行失败", e);
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
