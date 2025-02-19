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
import com.agentsflex.core.chain.Parameter;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.prompt.TextPrompt;
import com.agentsflex.core.prompt.template.TextPromptTemplate;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LlmNode extends BaseNode {

    protected Llm llm;
    protected ChatOptions chatOptions = ChatOptions.DEFAULT;
    protected String userPrompt;
    protected TextPromptTemplate userPromptTemplate;

    protected String systemPrompt;
    protected TextPromptTemplate systemPromptTemplate;
    protected String outType = "text"; //text markdown json

    public LlmNode() {
    }


    public LlmNode(Llm llm, String userPrompt) {
        this.llm = llm;
        this.userPrompt = userPrompt;
        this.userPromptTemplate = StringUtil.hasText(userPrompt)
            ? new TextPromptTemplate(userPrompt) : null;
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
        this.userPromptTemplate = StringUtil.hasText(userPrompt)
            ? new TextPromptTemplate(userPrompt) : null;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.systemPromptTemplate = StringUtil.hasText(systemPrompt)
            ? new TextPromptTemplate(systemPrompt) : null;
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

    public String getOutType() {
        return outType;
    }

    public void setOutType(String outType) {
        this.outType = outType;
    }

    @Override
    protected Map<String, Object> execute(Chain chain) {
        Map<String, Object> parameters = getParameters(chain);

        if (userPromptTemplate == null) {
            return Collections.emptyMap();
        }

        TextPrompt userPrompt = userPromptTemplate.format(parameters);

        if (systemPromptTemplate != null) {
            String systemPrompt = systemPromptTemplate.formatToString(parameters);
            userPrompt.setSystemMessage(SystemMessage.of(systemPrompt));
        }

        AiMessageResponse response = llm.chat(userPrompt, chatOptions);
        chain.output(this, response);

        if (response.isError()) {
            chain.stopError(response.getErrorMessage());
            return Collections.emptyMap();
        }

        if (outType == null || outType.equalsIgnoreCase("text") || outType.equalsIgnoreCase("markdown")) {
            return Maps.of("output", response.getMessage().getContent());
        } else {
            if (this.outputDefs != null) {
                JSONObject jsonObject;
                try {
                    jsonObject = JSON.parseObject(response.getResponse());
                } catch (Exception e) {
                    chain.stopError("Can not parse json: " + response.getResponse() + " " + e.getMessage());
                    return Collections.emptyMap();
                }
                Map<String, Object> map = new HashMap<>();
                for (Parameter outputDef : this.outputDefs) {
                    map.put(outputDef.getName(), jsonObject.get(outputDef.getName()));
                }
                return map;
            }

            return Collections.emptyMap();
        }
    }


    @Override
    public String toString() {
        return "LlmNode{" +
            "llm=" + llm +
            ", chatOptions=" + chatOptions +
            ", userPrompt='" + userPrompt + '\'' +
            ", userPromptTemplate=" + userPromptTemplate +
            ", systemPrompt='" + systemPrompt + '\'' +
            ", systemPromptTemplate=" + systemPromptTemplate +
            ", outType='" + outType + '\'' +
            ", description='" + description + '\'' +
            ", parameters=" + parameters +
            ", outputDefs=" + outputDefs +
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
