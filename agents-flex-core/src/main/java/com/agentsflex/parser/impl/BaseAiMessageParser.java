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
package com.agentsflex.parser.impl;

import com.agentsflex.message.AiMessage;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.Parser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;


public class BaseAiMessageParser implements AiMessageParser {

    private String contentPath;
    private String indexPath;
    private String statusPath;
    private String totalTokensPath;
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

    public Parser<Object, MessageStatus> getStatusParser() {
        return statusParser;
    }

    public void setStatusParser(Parser<Object, MessageStatus> statusParser) {
        this.statusParser = statusParser;
    }

    @Override
    public AiMessage parse(String content) {
        AiMessage aiMessage = new AiMessage();
        JSONObject rootJson = JSON.parseObject(content);
        aiMessage.setContent((String) JSONPath.eval(rootJson, this.contentPath));
        aiMessage.setIndex((Integer) JSONPath.eval(rootJson, this.indexPath));
        aiMessage.setTotalTokens((Integer) JSONPath.eval(rootJson, this.totalTokensPath));

        String statusString = (String) JSONPath.eval(rootJson, this.statusPath);
        if (this.statusParser != null) {
            aiMessage.setStatus(this.statusParser.parse(statusString));
        }

        return aiMessage;
    }
}
