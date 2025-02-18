package com.agentsflex.core.prompt;

import com.agentsflex.core.functions.Function;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;

public class FunctionPrompt extends Prompt {

    private SystemMessage systemMessage;
    private HumanMessage humanMessage;
    private boolean autoCall = true;

    public FunctionPrompt(String message, Class<?> functionsClass) {
        this.humanMessage = new HumanMessage(message);
        this.humanMessage.addFunctions(functionsClass);
    }

    public FunctionPrompt(String message, List<Function> functions) {
        this.humanMessage = new HumanMessage(message);
        this.humanMessage.addFunctions(functions);
    }

    public SystemMessage getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(SystemMessage systemMessage) {
        this.systemMessage = systemMessage;
    }

    public HumanMessage getHumanMessage() {
        return humanMessage;
    }

    public void setHumanMessage(HumanMessage humanMessage) {
        this.humanMessage = humanMessage;
    }

    public boolean isAutoCall() {
        return autoCall;
    }

    public void setAutoCall(boolean autoCall) {
        this.autoCall = autoCall;
    }

    @Override
    public List<Message> toMessages() {
        List<Message> messages = new ArrayList<>();
        if (systemMessage != null) {
            messages.add(0, systemMessage);
        }

        messages.add(humanMessage);
        return messages;
    }
}
