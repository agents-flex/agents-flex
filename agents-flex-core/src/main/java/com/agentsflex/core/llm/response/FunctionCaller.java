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
package com.agentsflex.core.llm.response;

import com.agentsflex.core.functions.Function;
import com.agentsflex.core.message.FunctionCall;

public class FunctionCaller {
    private final Function function;
    private final FunctionCall functionCall;

    public FunctionCaller(Function function, FunctionCall functionCall) {
        this.function = function;
        this.functionCall = functionCall;
    }

    public Object call() {
        return this.function.invoke(this.functionCall.getArgs());
    }

    public Function getFunction() {
        return function;
    }

    public FunctionCall getFunctionCall() {
        return functionCall;
    }
}
