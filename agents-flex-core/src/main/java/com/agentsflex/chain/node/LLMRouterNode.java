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
package com.agentsflex.chain.node;

import com.agentsflex.chain.Chain;
import com.agentsflex.chain.ChainNode;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.prompt.TextPrompt;
import com.agentsflex.prompt.template.TextPromptTemplate;

import java.util.List;

public class LLMRouterNode extends RouterNode {
    private Llm llm;
    private String prompt;

    public LLMRouterNode() {
    }

    public LLMRouterNode(Llm llm, String prompt) {
        this.llm = llm;
        this.prompt = prompt;
    }

    public LLMRouterNode(List<ChainNode> nodes, Llm llm, String prompt) {
        super(nodes);
        this.llm = llm;
        this.prompt = prompt;
    }

    public LLMRouterNode(List<ChainNode> nodes) {
        super(nodes);
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
    protected String route(Chain chain) {
        TextPromptTemplate promptTemplate = TextPromptTemplate.create(prompt);
        TextPrompt textPrompt = promptTemplate.format(chain.getMemory().getAll());
        AiMessageResponse response = llm.chat(textPrompt);
        return response.getMessage().getContent();
    }

    @Override
    public String toString() {
        return "LLMRouterNode{" +
            "llm=" + llm +
            ", prompt='" + prompt + '\'' +
            ", id=" + id +
            ", skip=" + skip +
            '}';
    }
}
