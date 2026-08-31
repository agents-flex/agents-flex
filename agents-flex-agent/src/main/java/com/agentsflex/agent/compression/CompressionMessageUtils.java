package com.agentsflex.agent.compression;

import com.agentsflex.core.message.AbstractTextMessage;
import com.agentsflex.core.message.Message;

/** Internal defensive-copy helper for compression inputs and results. */
final class CompressionMessageUtils {
    private CompressionMessageUtils() {
    }

    static Message copyMessage(Message message) {
        if (message == null) return null;
        if (message instanceof AbstractTextMessage) {
            return ((AbstractTextMessage<?>) message).copy();
        }
        throw new IllegalStateException("Unsupported compression message type: " + message.getClass().getName());
    }
}
