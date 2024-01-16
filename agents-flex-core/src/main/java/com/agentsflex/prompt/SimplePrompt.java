package com.agentsflex.prompt;

import com.agentsflex.message.HumanMessage;
import com.agentsflex.message.Message;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SimplePrompt extends Prompt{

    private final String content;

    public SimplePrompt(String content) {
        this.content = content;
    }

    @Override
    public List<Message> toMessages() {
        return Collections.singletonList(new HumanMessage(content));
    }
}
