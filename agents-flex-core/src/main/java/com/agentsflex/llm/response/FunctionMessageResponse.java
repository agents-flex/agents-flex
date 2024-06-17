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

public class FunctionMessageResponse extends AiMessageResponse {

    private final List<Function> functions;
    private final FunctionMessage functionMessage;
    private final Object functionResult;

    public FunctionMessageResponse(List<Function> functions, FunctionMessage functionMessage) {
        super(functionMessage);

        this.functions = functions;
        this.functionMessage = functionMessage;
        this.functionResult = invoke();

        if (this.functionMessage != null) {
            String messageContent = this.functionResult != null ? this.functionResult.toString() : null;
            this.functionMessage.setContent(messageContent);
        }
    }


    private Object invoke() {
        if (functionMessage == null) {
            return null;
        }
        Object result = null;
        for (Function function : functions) {
            if (function.getName().equals(functionMessage.getFunctionName())) {
                result = function.invoke(functionMessage.getArgs());
                break;
            }
        }
        return result;
    }

    @Override
    public FunctionMessage getMessage() {
        return functionMessage;
    }

    public Object getFunctionResult() {
        return functionResult;
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
