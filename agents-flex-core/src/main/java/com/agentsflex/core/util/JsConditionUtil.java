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
package com.agentsflex.core.util;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.util.graalvm.JsInteropUtils;
import org.graalvm.polyglot.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsConditionUtil {

    // 使用 Context.Builder 构建上下文，线程安全
    private static final Context.Builder CONTEXT_BUILDER = Context.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .allowHostAccess(HostAccess.ALL)       // 允许访问 Java 对象的方法和字段
        .allowHostClassLookup(className -> false) // 禁止动态加载任意 Java 类
        .option("js.ecmascript-version", "2021");  // 使用较新的 ECMAScript 版本

    /**
     * 执行 JavaScript 表达式并返回 boolean 结果
     *
     * @param code    JS 表达式（应返回布尔或可转换为布尔的值）
     * @param chain   Chain 上下文对象
     * @param initMap 初始变量映射
     * @return true 表示满足条件，继续执行；false 表示跳过
     */
    public static boolean eval(String code, Chain chain, Map<String, Object> initMap) {
        try (Context context = CONTEXT_BUILDER.build()) {
            Value bindings = context.getBindings("js");

            // 合并上下文变量
            Map<String, Object> contextVariables = collectContextVariables(chain, initMap);
            contextVariables.forEach((key, value) -> {
                bindings.putMember(key, JsInteropUtils.wrapJavaValueForJS(context, value));
            });

            // 执行脚本并获取结果
            Value result = context.eval("js", code);
            return toBoolean(result);
        } catch (Exception e) {
            throw new RuntimeException("JavaScript 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 收集上下文中的变量
     */
    private static Map<String, Object> collectContextVariables(Chain chain, Map<String, Object> initMap) {
        Map<String, Object> variables = new ConcurrentHashMap<>();

        // 添加 Chain Memory 中的变量（去掉前缀）
        chain.getMemory().getAll().forEach((key, value) -> {
            int dotIndex = key.indexOf(".");
            String varName = (dotIndex >= 0) ? key.substring(dotIndex + 1) : key;
            variables.put(varName, value);
        });

        // 添加 _chain 和 initMap 变量
        variables.putAll(initMap);

        return variables;
    }

    /**
     * 将任意对象转换为布尔值
     */
    private static boolean toBoolean(Value value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.isNumber()) {
            return value.asDouble() != 0;
        } else if (value.isString()) {
            String str = value.asString().trim().toLowerCase();
            return !str.isEmpty() && !"0".equals(str) && !"false".equals(str);
        } else {
            return !value.isNull();
        }
    }
}
