package com.agentsflex.core.model.chat.log;


import com.agentsflex.core.model.chat.ChatConfig;

public final class ChatMessageLogger {

    private static IChatMessageLogger logger = new DefaultChatMessageLogger();

    private ChatMessageLogger() {}

    public static void setLogger(IChatMessageLogger logger) {
        if (logger == null){
            throw new IllegalArgumentException("logger can not be null.");
        }
        ChatMessageLogger.logger = logger;
    }

    public static void logRequest(ChatConfig config, String message) {
        logger.logRequest(config, message);
    }

    public static void logResponse(ChatConfig config, String message) {
        logger.logResponse(config, message);
    }
}
