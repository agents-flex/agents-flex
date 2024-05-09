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
package com.agentsflex.agent;

import com.agentsflex.chain.Chain;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.SimplePrompt;
import com.agentsflex.prompt.template.SimplePromptTemplate;

import java.util.List;
import java.util.Map;

public class LLMAgent extends Agent {

    protected Llm llm;
    protected String prompt;

    public LLMAgent() {
    }


    public LLMAgent(Llm llm, String prompt) {
        this.llm = llm;
        this.prompt = prompt;
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
    }

    @Override
    public List<Parameter> defineInputParameter() {
        return null;
    }

    @Override
    public Output execute(Map<String, Object> variables, Chain chain) {
        SimplePromptTemplate promptTemplate = SimplePromptTemplate.create(prompt);
        SimplePrompt simplePrompt = promptTemplate.format(chain == null ? variables : chain.getMemory().getAll());
        AiMessageResponse response = llm.chat(simplePrompt);
        return parseAiMessage(response.getMessage());
    }

    protected Output parseAiMessage(AiMessage aiMessage) {
        return Output.ofValue(aiMessage.getContent());
    }

}
