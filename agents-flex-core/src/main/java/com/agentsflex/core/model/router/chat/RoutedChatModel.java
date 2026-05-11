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
package com.agentsflex.core.model.router.chat;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.router.balance.LeastActiveLoadBalancer;
import com.agentsflex.core.model.router.balance.ModelLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.breaker.DefaultCircuitBreaker;
import com.agentsflex.core.model.router.core.AbstractModelRouter;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.DefaultRetryPolicy;
import com.agentsflex.core.model.router.retry.RetryPolicy;
import com.agentsflex.core.prompt.Prompt;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class RoutedChatModel extends AbstractModelRouter<ChatModel> implements ChatModel {

    public RoutedChatModel(
        List<ModelEndpoint<ChatModel>> endpoints,
        ModelLoadBalancer<ChatModel> loadBalancer,
        RetryPolicy retryPolicy,
        CircuitBreaker<ChatModel> circuitBreaker) {

        super(
            endpoints,
            loadBalancer,
            retryPolicy,
            circuitBreaker
        );
    }

    public RoutedChatModel(List<ChatModel> models) {
        super(
            models.stream()
                .map(ModelEndpoint::new
                )
                .collect(Collectors.toList()),
            new LeastActiveLoadBalancer<>(),
            new DefaultRetryPolicy(3),
            new DefaultCircuitBreaker<>()
        );
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        return execute(
            model -> model.chat(prompt, options),
            extractTags(options)
        );
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        execute(model -> {
            model.chatStream(
                prompt,
                listener,
                options
            );
            return null;
        }, extractTags(options));
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractTags(ChatOptions options) {

        if (options == null) {
            return Collections.emptySet();
        }

        Object value = options.getMetadata("modelTags");

        if (value instanceof Set<?>) {
            return (Set<String>) value;
        }

        return Collections.emptySet();
    }
}
