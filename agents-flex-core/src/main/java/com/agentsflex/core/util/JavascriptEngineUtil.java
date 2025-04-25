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

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Map;

public class JavascriptEngineUtil {

    public static boolean eval(String code, Chain chain, Map<String, Object> initMap) {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");

        if (engine == null) {
            throw new RuntimeException("未找到 GraalJS 引擎，请确认依赖配置");
        }

        // 配置引擎参数（通过Bindings）
        Bindings bindings = engine.createBindings();
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);

        // 获取并注入参数
        Map<String, Object> chainMemory = chain.getMemory().getAll();

        Map<String, Object> parameterValues = new HashMap<>(chainMemory.size());
        chainMemory.forEach((key, value) -> {
            int index = key.indexOf(".");
            if (index >= 0) {
                parameterValues.put(key.substring(index + 1), value);
            } else {
                parameterValues.put(key, value);
            }
        });

        parameterValues.put("_chain", chain);
        parameterValues.putAll(initMap);

        bindings.putAll(parameterValues);

        Object result;
        try {
            result = engine.eval(code, bindings);
        } catch (ScriptException e) {
            throw new RuntimeException(e.toString(), e);
        }

        if (result == null) {
            return false;
        }

        String resultStr = result.toString().toLowerCase().trim();
        return !"0".equals(resultStr) && !"false".equalsIgnoreCase(resultStr);
    }


}
