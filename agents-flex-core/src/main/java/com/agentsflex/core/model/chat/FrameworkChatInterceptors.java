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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.prompt.Prompt;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Framework-owned interceptor registrations with overridable default ordering.
 */
final class FrameworkChatInterceptors {

    private static final List<ChatInterceptorRegistration> REGISTRATIONS =
        Collections.unmodifiableList(Arrays.asList(
            // 可观测
            ChatInterceptorRegistration.builder("chat-observability", new ChatObservabilityInterceptor())
                .order(ChatInterceptorOrders.OBSERVABILITY)
                .matcher(context -> context != null
                    && context.getConfig() != null
                    && context.getConfig().isObservabilityEnabled())
                .build(),

            // Tool Group
            ChatInterceptorRegistration.builder("tool-group-resolver", new ToolGroupChatInterceptor())
                .order(ChatInterceptorOrders.REQUEST_PREPARATION)
                .matcher(context -> {
                    Prompt prompt = context == null ? null : context.getPrompt();
                    return prompt != null && !prompt.getToolGroups().isEmpty();
                })
                .build()
        ));

    private FrameworkChatInterceptors() {
    }

    static List<ChatInterceptorRegistration> getRegistrations() {
        return REGISTRATIONS;
    }
}
