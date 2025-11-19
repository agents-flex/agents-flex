package com.agentsflex.core.message;

public class AbstractTextMessage extends Message{

    protected String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public Object getMessageContent() {
        return content;
    }

    @Override
    public String getTextContent() {
        return content;
    }
}
