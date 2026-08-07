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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
            model -> {
                AiMessageResponse response = model.chat(prompt, options);
                if (response != null && response.isError()) {
                    response.throwIfError();
                }
                return response;
            },
            extractTags(options)
        );
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        Set<ModelEndpoint<ChatModel>> attempted = new java.util.HashSet<>();
        streamAttempt(prompt, listener, options, attempted, 0, null);
    }

    private void streamAttempt(Prompt prompt, StreamResponseListener listener, ChatOptions options,
                               Set<ModelEndpoint<ChatModel>> attempted, int retryCount,
                               Throwable previous) {
        List<ModelEndpoint<ChatModel>> candidates = filterAvailable(extractTags(options), attempted);
        if (candidates.isEmpty()) {
            attempted.clear();
            candidates = filterAvailable(extractTags(options), attempted);
        }
        if (candidates.isEmpty())
            throw new com.agentsflex.core.model.router.core.RouterException("No available model endpoint.", previous);
        ModelEndpoint<ChatModel> endpoint = loadBalancer.select(candidates);
        attempted.add(endpoint);
        endpoint.getMetrics().beginRequest();
        long start = System.currentTimeMillis();
        AtomicBoolean delivered = new AtomicBoolean();
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean switched = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamResponseListener wrapped = new StreamResponseListener() {
            @Override
            public void onOpen(com.agentsflex.core.model.client.StreamContext context) {
                opened.set(true);
            }

            @Override
            public void onMessage(com.agentsflex.core.model.client.StreamContext context, AiMessageResponse response) {
                if (response != null && response.isError()) {
                    onError(context, response.toException());
                    return;
                }
                delivered.set(true);
                if (opened.compareAndSet(false, true)) listener.onOpen(context);
                listener.onMessage(context, response);
            }

            @Override
            public void onError(com.agentsflex.core.model.client.StreamContext context, Throwable error) {
                failed.set(true);
                failure.compareAndSet(null, error);
                if (!delivered.get() && retryPolicy.shouldRetry(retryCount, error)) {
                    switched.set(true);
                    finished.set(true);
                    endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                    if (shouldRecordEndpointFailure(error)) circuitBreaker.recordFailure(endpoint);
                    endpoint.getMetrics().endRequest();
                    streamAttempt(prompt, listener, options, attempted, retryCount + 1, error);
                    return;
                }
                if (opened.compareAndSet(false, true)) listener.onOpen(context);
                listener.onError(context, error);
            }

            @Override
            public void onClose(com.agentsflex.core.model.client.StreamContext context) {
                if (!finished.compareAndSet(false, true)) return;
                if (!switched.get()) {
                    listener.onClose(context);
                    if (failed.get()) {
                        endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                        if (shouldRecordEndpointFailure(failure.get())) {
                            circuitBreaker.recordFailure(endpoint);
                        }
                    } else {
                        endpoint.getMetrics().recordSuccess(System.currentTimeMillis() - start);
                        circuitBreaker.recordSuccess(endpoint);
                    }
                }
                endpoint.getMetrics().endRequest();
            }
        };
        try {
            endpoint.getModel().chatStream(prompt, wrapped, options);
        } catch (Exception e) {
            if (!delivered.get() && retryPolicy.shouldRetry(retryCount, e)) {
                endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                if (shouldRecordEndpointFailure(e)) circuitBreaker.recordFailure(endpoint);
                endpoint.getMetrics().endRequest();
                streamAttempt(prompt, listener, options, attempted, retryCount + 1, e);
            } else {
                endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                endpoint.getMetrics().endRequest();
                throw new com.agentsflex.core.model.router.core.RouterException("All model requests failed.", e);
            }
        }
    }

    private List<ModelEndpoint<ChatModel>> filterAvailable(Set<String> tags, Set<ModelEndpoint<ChatModel>> attempted) {
        return endpoints.stream().filter(e -> !attempted.contains(e))
            .filter(e -> e.getStatus() != com.agentsflex.core.model.router.endpoint.EndpointStatus.DOWN)
            .filter(circuitBreaker::allowRequest).filter(e -> e.matchTags(tags)).collect(Collectors.toList());
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
