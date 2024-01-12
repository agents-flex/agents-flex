package com.agentsflex.llm;

import com.agentsflex.message.AiMessage;

public interface ChatListener {

    default void onStart(Llm llm){}

    void onMessage(Llm llm, AiMessage aiMessage);

    default void onStop(Llm llm){}

    default void onFailure(Llm llm, Throwable throwable){}
}
