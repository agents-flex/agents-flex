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
package com.agentsflex.llm.response;

import com.agentsflex.functions.Function;
import com.agentsflex.message.FunctionMessage;

import java.util.List;

public class FunctionMessageResponse extends AbstractBaseMessageResponse<FunctionMessage> {

    private final List<Function> functions;
    private final FunctionMessage functionMessage;

    public FunctionMessageResponse(List<Function> functions, FunctionMessage functionMessage) {
        this.functions = functions;
        this.functionMessage = functionMessage;
    }

    public Object invoke() {
        if (functionMessage == null) {
            return null;
        }
        for (Function function : functions) {
            if (function.getName().equals(functionMessage.getFunctionName())) {
                return function.invoke(functionMessage.getArgs());
            }
        }
        return null;
    }

    @Override
    public FunctionMessage getMessage() {
        return functionMessage;
    }

    @Override
    public String toString() {
        return "FunctionMessageResponse{" +
            "functions=" + functions +
            ", functionMessage=" + functionMessage +
            ", isError=" + isError +
            ", errorMessage='" + errorMessage + '\'' +
            ", errorType='" + errorType + '\'' +
            ", errorCode='" + errorCode + '\'' +
            '}';
    }
}
