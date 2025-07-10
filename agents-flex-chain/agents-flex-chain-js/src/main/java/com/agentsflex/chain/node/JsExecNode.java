/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.chain.node;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.node.CodeNode;
import com.agentsflex.core.util.graalvm.JsInteropUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.Map;

public class JsExecNode extends CodeNode {

    // 使用 Context.Builder 构建上下文，线程安全
    private static final Context.Builder CONTEXT_BUILDER = Context.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .allowHostAccess(HostAccess.ALL)       // 允许访问 Java 对象的方法和字段
        .allowHostClassLookup(className -> false) // 禁止动态加载任意 Java 类
        .option("js.ecmascript-version", "2021");  // 使用较新的 ECMAScript 版本


    @Override
    protected Map<String, Object> executeCode(String code, Chain chain) {
        try (Context context = CONTEXT_BUILDER.build()) {
            Value bindings = context.getBindings("js");

            Map<String, Object> all = chain.getMemory().getAll();
            all.forEach((key, value) -> {
                if (!key.contains(".")) {
                    bindings.putMember(key, JsInteropUtils.wrapJavaValueForJS(context, value));
                }
            });

            // 注入参数
            Map<String, Object> parameterValues = chain.getParameterValues(this);
            if (parameterValues != null) {
                for (Map.Entry<String, Object> entry : parameterValues.entrySet()) {
                    bindings.putMember(entry.getKey(), JsInteropUtils.wrapJavaValueForJS(context, entry.getValue()));
                }
            }

            Map<String, Object> result = new HashMap<>();
            bindings.putMember("_result", result);
            bindings.putMember("_chain", chain);
            bindings.putMember("_context", chain.getNodeContext(this));


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
