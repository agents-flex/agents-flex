/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
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
