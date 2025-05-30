package com.agentsflex.chain.node;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.node.CodeNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.Map;

public class JsExecNode extends CodeNode {

    // 可复用的 Context 构建器（每次执行脚本时新建一个独立 Context）
    private static final Context.Builder CONTEXT_BUILDER = Context.newBuilder("js")
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup(s -> true)
        .option("js.ecmascript-version", "2021"); // 可选 ECMAScript 版本


    @Override
    protected Map<String, Object> executeCode(String code, Chain chain) {
        try (Context context = CONTEXT_BUILDER.build()) {
            Value bindings = context.getBindings("js");

//            Map<String, Object> all = chain.getMemory().getAll();
//            all.forEach((key, value) -> {
//                if (!key.contains(".")) {
//                    bindings.putMember(key, value);
//                }
//            });

            // 注入参数
            Map<String, Object> parameterValues = chain.getParameterValues(this);
            if (parameterValues != null) {
                for (Map.Entry<String, Object> entry : parameterValues.entrySet()) {
                    bindings.putMember(entry.getKey(), entry.getValue());
                }
            }

            // 创建结果容器
            Map<String, Object> result = new HashMap<>();

            // 注入上下文对象
            bindings.putMember("_chain", chain);
            bindings.putMember("_result", result);

            // 执行脚本
            context.eval("js", code);

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Polyglot JS 脚本执行失败: " + e.getMessage(), e);
        }
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
