package com.agentsflex.core.model.chat.log;

import com.agentsflex.core.model.chat.ChatConfig;

public interface ChatMessageLogger {
    void logRequest(ChatConfig config, String message);
    void logResponse(ChatConfig config, String message);
}
