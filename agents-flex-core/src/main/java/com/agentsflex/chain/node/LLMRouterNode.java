/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
import com.agentsflex.prompt.SimplePrompt;
import com.agentsflex.prompt.template.SimplePromptTemplate;

import java.util.List;

public class LLMRouterNode extends RouterNode {
    private Llm llm;
    private String prompt;

    public LLMRouterNode(List<ChainNode> nodes) {
        super(nodes);
    }

    @Override
    protected String route(Chain chain) {
        SimplePromptTemplate promptTemplate = SimplePromptTemplate.create(prompt);
        SimplePrompt simplePrompt = promptTemplate.format(chain.getMemory().getAll());
        AiMessageResponse response = llm.chat(simplePrompt);
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
