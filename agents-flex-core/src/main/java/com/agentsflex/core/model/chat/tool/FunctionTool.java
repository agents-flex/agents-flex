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
package com.agentsflex.core.model.chat.tool;

import java.util.Map;
import java.util.function.Function;

public class FunctionTool extends BaseTool {

    private Function<Map<String, Object>, Object> invoker;

    public FunctionTool() {
    }

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        if (invoker == null) {
            throw new IllegalStateException("Tool invoker function is not set.");
        }
        return invoker.apply(argsMap);
    }

    // 允许外部设置 invoker（Builder 会用）
    public void setInvoker(Function<Map<String, Object>, Object> invoker) {
        this.invoker = invoker;
    }
}
