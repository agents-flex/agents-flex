/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.model.chat.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

public class TypedFunctionTool<T> extends BaseTool {

    private Class<T> inputType;
    private Function<T, ?> function;

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        T input;
        if (argsMap instanceof JSONObject) {
            input = ((JSONObject) argsMap).to(inputType);
        }
        // 理论上不存在这种情况，因为 framework 会自动将 argsMap 转换为 JSONObject
        else {
            input = JSON.parseObject(JSON.toJSONString(argsMap), inputType);
        }
        return function.apply(input);
    }

    public Class<T> getInputType() {
        return inputType;
    }

    public void setInputType(Class<T> inputType) {
        this.inputType = inputType;
    }

    public Function<T, ?> getFunction() {
        return function;
    }

    public void setFunction(Function<T, ?> function) {
        this.function = function;
    }

    @Override
    public String toString() {
        return "TypedFunctionTool{" +
            "inputType=" + inputType +
            ", function=" + function +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", parameters=" + Arrays.toString(parameters) +
            '}';
    }
}
