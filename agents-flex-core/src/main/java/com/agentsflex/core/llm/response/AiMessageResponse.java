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

import com.agentsflex.core.llm.functions.Function;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.CollectionUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AiMessageResponse extends AbstractBaseMessageResponse<AiMessage> {

    private Prompt prompt;
    private String response;
    private AiMessage message;

    public AiMessageResponse(Prompt prompt, String response, AiMessage message) {
        this.prompt = prompt;
        this.response = response;
        this.message = message;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    @Override
    public AiMessage getMessage() {
        return message;
    }

    public void setMessage(AiMessage message) {
        this.message = message;
    }

    public boolean isFunctionCall() {
        if (this.message == null) {
            return false;
        }
        List<FunctionCall> calls = message.getCalls();
        return calls != null && !calls.isEmpty();
    }


    public List<FunctionCaller> getFunctionCallers() {
        if (this.message == null) {
            return Collections.emptyList();
        }

        List<FunctionCall> calls = message.getCalls();
        if (calls == null || calls.isEmpty()) {
            return Collections.emptyList();
        }

        HumanMessage humanMessage = (HumanMessage) CollectionUtil.lastItem(prompt.toMessages().stream().filter(m -> m instanceof HumanMessage).collect(Collectors.toList()));
        Map<String, Function> funcMap = humanMessage.getFunctionMap();

        if (funcMap == null || funcMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<FunctionCaller> functionCallers = new ArrayList<>(calls.size());
        for (FunctionCall call : calls) {
            Function function = funcMap.get(call.getName());
            if (function != null) {
                functionCallers.add(new FunctionCaller(function, call));
            }
        }
        return functionCallers;
    }


    public List<Object> callFunctions() {
        List<FunctionCaller> functionCallers = getFunctionCallers();
        if (CollectionUtil.noItems(functionCallers)) {
            return Collections.emptyList();
        }
        List<Object> results = new ArrayList<>();
        for (FunctionCaller functionCaller : functionCallers) {
            results.add(functionCaller.call());
        }
        return results;
    }

    public static AiMessageResponse error(Prompt prompt, String response, String errorMessage) {
        AiMessageResponse errorResp = new AiMessageResponse(prompt, response, null);
        errorResp.setError(true);
        errorResp.setErrorMessage(errorMessage);
        return errorResp;
    }


    @Override
    public String toString() {
        return "AiMessageResponse{" +
            "prompt=" + prompt +
            ", response='" + response + '\'' +
            ", message=" + message +
            ", error=" + error +
            ", errorMessage='" + errorMessage + '\'' +
            ", errorType='" + errorType + '\'' +
            ", errorCode='" + errorCode + '\'' +
            '}';
    }
}
