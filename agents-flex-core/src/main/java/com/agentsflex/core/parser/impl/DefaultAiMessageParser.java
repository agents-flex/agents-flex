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
package com.agentsflex.core.parser.impl;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.Parser;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;


public class DefaultAiMessageParser implements AiMessageParser {

    private String contentPath;
    private String indexPath;
    private String statusPath;
    private String totalTokensPath;
    private String promptTokensPath;
    private String completionTokensPath;
    private Parser<Object, MessageStatus> statusParser;

    public String getContentPath() {
        return contentPath;
    }

    public void setContentPath(String contentPath) {
        this.contentPath = contentPath;
    }

    public String getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(String indexPath) {
        this.indexPath = indexPath;
    }

    public String getStatusPath() {
        return statusPath;
    }

    public void setStatusPath(String statusPath) {
        this.statusPath = statusPath;
    }

    public String getTotalTokensPath() {
        return totalTokensPath;
    }

    public void setTotalTokensPath(String totalTokensPath) {
        this.totalTokensPath = totalTokensPath;
    }

    public String getPromptTokensPath() {
        return promptTokensPath;
    }

    public void setPromptTokensPath(String promptTokensPath) {
        this.promptTokensPath = promptTokensPath;
    }

    public String getCompletionTokensPath() {
        return completionTokensPath;
    }

    public void setCompletionTokensPath(String completionTokensPath) {
        this.completionTokensPath = completionTokensPath;
    }

    public Parser<Object, MessageStatus> getStatusParser() {
        return statusParser;
    }

    public void setStatusParser(Parser<Object, MessageStatus> statusParser) {
        this.statusParser = statusParser;
    }

    @Override
    public AiMessage parse(JSONObject rootJson) {
        AiMessage aiMessage = new AiMessage();

        if (StringUtil.hasText(this.contentPath)) {
            aiMessage.setContent((String) JSONPath.eval(rootJson, this.contentPath));
        }

        if (StringUtil.hasText(this.indexPath)) {
            aiMessage.setIndex((Integer) JSONPath.eval(rootJson, this.indexPath));
        }


        if (StringUtil.hasText(promptTokensPath)) {
            aiMessage.setPromptTokens((Integer) JSONPath.eval(rootJson, this.promptTokensPath));
        }

        if (StringUtil.hasText(completionTokensPath)) {
            aiMessage.setCompletionTokens((Integer) JSONPath.eval(rootJson, this.completionTokensPath));
        }

        if (StringUtil.hasText(this.totalTokensPath)) {
            aiMessage.setTotalTokens((Integer) JSONPath.eval(rootJson, this.totalTokensPath));
        }
        //some LLMs like Ollama not response the total tokens
        else if (aiMessage.getPromptTokens() != null && aiMessage.getCompletionTokens() != null) {
            aiMessage.setTotalTokens(aiMessage.getPromptTokens() + aiMessage.getCompletionTokens());
        }

        if (StringUtil.hasText(this.statusPath)) {
            Object statusString = JSONPath.eval(rootJson, this.statusPath);
            if (this.statusParser != null) {
                aiMessage.setStatus(this.statusParser.parse(statusString));
            }
        }


        return aiMessage;
    }
}
