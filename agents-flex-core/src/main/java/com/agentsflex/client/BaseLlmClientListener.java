package com.agentsflex.client;

import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.Llm;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.HistoriesPrompt;
import com.agentsflex.prompt.Prompt;

public class BaseLlmClientListener implements LlmClientListener {

    private final Llm llm;
    private final ChatListener chatListener;

    private final Prompt prompt;

    private final MessageParser messageParser;

    private final StringBuilder fullMessage = new StringBuilder();

    private AiMessage lastAiMessage;

    public BaseLlmClientListener(Llm llm, ChatListener chatListener, Prompt prompt, MessageParser messageParser) {
        this.llm = llm;
        this.chatListener = chatListener;
        this.prompt = prompt;
        this.messageParser = messageParser;
    }


    @Override
    public void onStart(LlmClient client) {
        chatListener.onStart(llm);
    }

    @Override
    public void onMessage(LlmClient client, String response) {
        lastAiMessage =  messageParser.parseMessage(response);
        fullMessage.append(lastAiMessage.getContent());
        chatListener.onMessage(llm, lastAiMessage);
    }

    @Override
    public void onStop(LlmClient client) {
        if (lastAiMessage != null){

            lastAiMessage.setFullContent(fullMessage.toString());

            if (this.prompt instanceof HistoriesPrompt){
                ((HistoriesPrompt) this.prompt).addMessage(lastAiMessage);
            }
        }

        chatListener.onStop(llm);
    }

    @Override
    public void onFailure(LlmClient client, Throwable throwable) {
        chatListener.onFailure(llm, throwable);
    }


    public interface MessageParser{
        AiMessage parseMessage(String response);
    }
}
