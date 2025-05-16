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
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.prompt.TextPrompt;
import com.agentsflex.core.prompt.template.TextPromptTemplate;
import com.agentsflex.core.util.CollectionUtil;
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
            ? TextPromptTemplate.of(userPrompt) : null;
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
            ? TextPromptTemplate.of(userPrompt) : null;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.systemPromptTemplate = StringUtil.hasText(systemPrompt)
            ? TextPromptTemplate.of(systemPrompt) : null;
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
        Map<String, Object> parameterValues = chain.getParameterValues(this);

        if (userPromptTemplate == null) {
            return Collections.emptyMap();
        }

        TextPrompt userPrompt = userPromptTemplate.format(parameterValues);

        if (systemPromptTemplate != null) {
            String systemPrompt = systemPromptTemplate.formatToString(parameterValues);
            userPrompt.setSystemMessage(SystemMessage.of(systemPrompt));
        }

        AiMessageResponse response = llm.chat(userPrompt, chatOptions);
        chain.output(this, response);

        if (response == null) {
            return Collections.emptyMap();
        }

        if (response.isError()) {
            chain.stopError(response.getErrorMessage());
            return Collections.emptyMap();
        }

        AiMessage aiMessage = response.getMessage();
        if (aiMessage == null) {
            return Collections.emptyMap();
        }


        String responseContent = aiMessage.getContent();
        if (StringUtil.noText(responseContent)) {
            chain.stopError("Can not get response content: " + response.getResponse());
            return Collections.emptyMap();
        } else {
            responseContent = responseContent.trim();
        }


        if (outType == null || outType.equalsIgnoreCase("text") || outType.equalsIgnoreCase("markdown")) {
            if (CollectionUtil.noItems(this.outputDefs)) {
                return Maps.of("output", responseContent);
            } else {
                Parameter parameter = this.outputDefs.get(0);
                return Maps.of(parameter.getName(), responseContent);
            }
        } else {
            if (this.outputDefs != null) {
                JSONObject jsonObject;
                try {
                    jsonObject = JSON.parseObject(unWrapMarkdown(responseContent));
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


    /**
     * 移除 ``` 或者 ```json 等
     *
     * @param markdown json内容
     * @return 方法 json 内容
     */
    private String unWrapMarkdown(String markdown) {
        // 移除开头的 ```json 或 ```
        if (markdown.startsWith("```")) {
            int newlineIndex = markdown.indexOf('\n');
            if (newlineIndex != -1) {
                markdown = markdown.substring(newlineIndex + 1);
            } else {
                // 如果没有换行符，直接去掉 ``` 部分
                markdown = markdown.substring(3);
            }
        }

        // 移除结尾的 ```
        if (markdown.endsWith("```")) {
            markdown = markdown.substring(0, markdown.length() - 3);
        }
        return markdown.trim();
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
