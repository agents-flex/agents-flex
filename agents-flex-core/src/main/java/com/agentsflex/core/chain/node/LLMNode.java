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
package com.agentsflex.core.chain.node;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.OutputKey;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.TextPrompt;
import com.agentsflex.core.prompt.template.TextPromptTemplate;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LLMNode extends BaseNode {

    protected Llm llm;
    protected ChatOptions chatOptions = ChatOptions.DEFAULT;
    protected String userPrompt;
    protected TextPromptTemplate userPromptTemplate;

    protected String systemPrompt;
    protected TextPromptTemplate systemPromptTemplate;

    public LLMNode() {
    }


    public LLMNode(Llm llm, String userPrompt) {
        this.llm = llm;
        this.userPrompt = userPrompt;
        this.userPromptTemplate = new TextPromptTemplate(userPrompt);
    }


    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public void setUserPrompt(String userPrompt) {
        this.userPrompt = userPrompt;
        this.userPromptTemplate = new TextPromptTemplate(userPrompt);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.systemPromptTemplate = StringUtil.hasText(systemPrompt) ? new TextPromptTemplate(systemPrompt) : null;
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
    protected Map<String, Object> execute(Chain chain) {
        Map<String, Object> parameters = getParameters(chain);

        if (userPromptTemplate == null){
            chain.stopError("user prompt is null or empty");
            return Collections.emptyMap();
        }





        TextPrompt userPrompt = userPromptTemplate.format(parameters);
        AiMessageResponse response = llm.chat(userPrompt, chatOptions);

        if (chain != null) {
            chain.output(this, response);
        }

        return response.isError()
            ? onError(response, chain)
            : onMessage(response.getMessage());
    }


    protected Map<String, Object> onError(AiMessageResponse response, Chain chain) {
        if (chain != null) {
            chain.stopError(response.getErrorMessage());
        }
        return null;
    }


    protected Map<String, Object> onMessage(AiMessage aiMessage) {
        List<OutputKey> outputKeys = getOutputKeys();
        if (outputKeys != null && outputKeys.size() == 1) {
            return Maps.of("content", aiMessage.getContent()).build();
        }

        return Maps.of("content", aiMessage.getContent()).build();
    }


    @Override
    public String toString() {
        return "LLMNode{" +
            "llm=" + llm +
            ", chatOptions=" + chatOptions +
            ", prompt='" + userPrompt + '\'' +
            ", promptTemplate=" + userPromptTemplate +
            ", description='" + description + '\'' +
            ", inputParameters=" + inputParameters +
            ", outputKeys=" + outputKeys +
            ", id='" + id + '\'' +
            ", name='" + name + '\'' +
            ", async=" + async +
            ", inwardEdges=" + inwardEdges +
            ", outwardEdges=" + outwardEdges +
            ", condition=" + condition +
            ", memory=" + memory +
            ", nodeStatus=" + nodeStatus +
            '}';
    }
}
