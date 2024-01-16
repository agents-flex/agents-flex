package com.agentsflex.prompt;

import com.agentsflex.message.Message;

import java.util.ArrayList;
import java.util.List;

public class HistoriesPrompt extends Prompt{

    private final List<Message> messages = new ArrayList<>();


    public void addMessage(Message message) {
        messages.add(message);
    }


    @Override
    public List<Message> toMessages() {
        return messages;
    }
}
