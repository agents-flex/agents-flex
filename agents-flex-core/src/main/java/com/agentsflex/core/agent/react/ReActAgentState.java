package com.agentsflex.core.agent.react;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ReActAgentState implements Serializable {

    private static final long serialVersionUID = 1L;

    String userQuery;
    List<Message> messageHistory;
    int iterationCount = 0;
    int maxIterations;
    boolean streamable;
    String promptTemplate;
    boolean continueOnActionInvokeError;

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public List<Message> getMessageHistory() {
        return messageHistory;
    }

    public void setMessageHistory(List<Message> messageHistory) {
        this.messageHistory = messageHistory;
    }

    public void addMessage(UserMessage message) {
        if (messageHistory == null) {
            messageHistory = new ArrayList<>();
        }
        messageHistory.add(message);
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public void setIterationCount(int iterationCount) {
        this.iterationCount = iterationCount;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public boolean isStreamable() {
        return streamable;
    }

    public void setStreamable(boolean streamable) {
        this.streamable = streamable;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    public boolean isContinueOnActionInvokeError() {
        return continueOnActionInvokeError;
    }

    public void setContinueOnActionInvokeError(boolean continueOnActionInvokeError) {
        this.continueOnActionInvokeError = continueOnActionInvokeError;
    }

    public String toJSON() {
        return JSON.toJSONString(this, JSONWriter.Feature.WriteClassName);
    }

    public static ReActAgentState fromJSON(String json) {
        return JSON.parseObject(json, ReActAgentState.class, JSONReader.Feature.SupportClassForName);
    }


}
