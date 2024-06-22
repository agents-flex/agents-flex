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
package com.agentsflex.core.agent;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.prompt.TextPrompt;
import com.agentsflex.core.prompt.template.TextPromptTemplate;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LLMAgent extends Agent {

    protected Llm llm;
    protected ChatOptions chatOptions = ChatOptions.DEFAULT;
    protected String prompt;
    protected TextPromptTemplate promptTemplate;

    public LLMAgent() {
    }


    public LLMAgent(Llm llm, String prompt) {
        this.llm = llm;
        this.prompt = prompt;
        this.promptTemplate = new TextPromptTemplate(prompt);
    }


    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
        this.promptTemplate = new TextPromptTemplate(prompt);
    }

    public ChatOptions getChatOptions() {
        return chatOptions;
    }

    public void setChatOptions(ChatOptions chatOptions) {
        if (chatOptions == null) {
            chatOptions = ChatOptions.DEFAULT;
        }
        this.chatOptions = chatOptions;
    }


    @Override
    public List<Parameter> defineInputParameter() {
        if (this.promptTemplate == null) {
            return null;
        }

        Set<String> keys = this.promptTemplate.getKeys();
        if (keys == null || keys.isEmpty()) {
            return null;
        }

        List<Parameter> parameters = new ArrayList<>(keys.size());
        for (String key : keys) {
            Parameter parameter = new Parameter(key, true);
            parameters.add(parameter);
        }
        return parameters;
    }


    @Override
    public Output execute(Map<String, Object> variables, Chain chain) {
        TextPrompt textPrompt = promptTemplate.format(variables);
        AiMessageResponse response = llm.chat(textPrompt, chatOptions);

        if (chain != null) {
            chain.output(this, response);
        }

        return response.isError()
            ? onError(response, chain)
            : onMessage(response.getMessage());
    }


    protected Output onError(AiMessageResponse response, Chain chain) {
        if (chain != null) {
            chain.stopError(response.getErrorMessage());
        }
        return null;
    }


    protected Output onMessage(AiMessage aiMessage) {
        List<OutputKey> outputKeys = getOutputKeys();
        if (outputKeys != null && outputKeys.size() == 1) {
            return Output.of(outputKeys.get(0), aiMessage.getContent());
        }

        return Output.ofDefault(aiMessage.getContent());
    }

    @Override
    public String toString() {
        return "LLMAgent{" +
            "llm=" + llm +
            ", prompt='" + prompt + '\'' +
            ", id=" + id +
            ", name='" + name + '\'' +
            '}';
    }
}
