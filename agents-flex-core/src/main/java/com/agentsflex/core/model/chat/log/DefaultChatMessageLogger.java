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

import java.util.function.Consumer;

public class DefaultChatMessageLogger implements IChatMessageLogger {

    private final Consumer<String> logConsumer;

    public DefaultChatMessageLogger() {
        this(System.out::println);
    }

    public DefaultChatMessageLogger(Consumer<String> logConsumer) {
        this.logConsumer = logConsumer != null ? logConsumer : System.out::println;
    }

    @Override
    public void logRequest(ChatConfig config, String message) {
        if (shouldLog(config)) {
            String provider = getProviderName(config);
            String model = getModelName(config);
            logConsumer.accept(String.format("[%s/%s] >>>> request: %s", provider, model, message));
        }
    }

    @Override
    public void logResponse(ChatConfig config, String message) {
        if (shouldLog(config)) {
            String provider = getProviderName(config);
            String model = getModelName(config);
            logConsumer.accept(String.format("[%s/%s] <<<< response: %s", provider, model, message));
        }
    }

    private boolean shouldLog(ChatConfig config) {
        return config != null && config.isLogEnabled();
    }

    private String getProviderName(ChatConfig config) {
        String provider = config.getProvider();
        return provider != null ? provider : "unknow";
    }

    private String getModelName(ChatConfig config) {
        String model = config.getModel();
        return model != null ? model : "unknow";
    }
}
