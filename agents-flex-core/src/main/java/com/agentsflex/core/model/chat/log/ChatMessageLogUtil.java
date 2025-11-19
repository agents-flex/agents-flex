package com.agentsflex.core.model.chat.log;


import com.agentsflex.core.model.chat.ChatConfig;

public final class ChatMessageLogUtil {

    private static ChatMessageLogger logger = new DefaultChatMessageLogger();

    private ChatMessageLogUtil() {}

    public static void setLogger(ChatMessageLogger logger) {
        if (logger == null){
            throw new IllegalArgumentException("logger can not be null.");
        }
        ChatMessageLogUtil.logger = logger;
    }

    // 快捷静态方法
    public static void logRequest(ChatConfig config, String message) {
        logger.logRequest(config, message);
    }

    public static void logResponse(ChatConfig config, String message) {
        logger.logResponse(config, message);
    }
}
