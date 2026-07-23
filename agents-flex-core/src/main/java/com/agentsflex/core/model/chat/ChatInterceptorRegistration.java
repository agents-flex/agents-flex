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

/**
 * Immutable registration describing a chat interceptor and the requests to which it applies.
 */
public final class ChatInterceptorRegistration {

    private static final ChatInterceptorMatcher ALWAYS = context -> true;

    private final String name;
    private final ChatInterceptor interceptor;
    private final ChatInterceptorMatcher matcher;
    private final int order;

    private ChatInterceptorRegistration(Builder builder) {
        this.name = builder.name;
        this.interceptor = builder.interceptor;
        this.matcher = builder.matcher;
        this.order = builder.order;
    }

    public static ChatInterceptorRegistration of(ChatInterceptor interceptor) {
        requireInterceptor(interceptor);
        return builder(interceptor.getClass().getName(), interceptor).build();
    }

    public static Builder builder(String name, ChatInterceptor interceptor) {
        return new Builder(name, interceptor);
    }

    public String getName() {
        return name;
    }

    public ChatInterceptor getInterceptor() {
        return interceptor;
    }

    public ChatInterceptorMatcher getMatcher() {
        return matcher;
    }

    public int getOrder() {
        return order;
    }

    public boolean matches(ChatContext context) {
        return matcher.matches(context);
    }

    private static void requireInterceptor(ChatInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("ChatInterceptor must not be null");
        }
    }

    public static final class Builder {
        private final String name;
        private final ChatInterceptor interceptor;
        private ChatInterceptorMatcher matcher = ALWAYS;
        private int order = ChatInterceptorOrders.DEFAULT;

        private Builder(String name, ChatInterceptor interceptor) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Chat interceptor registration name must not be blank");
            }
            requireInterceptor(interceptor);
            this.name = name;
            this.interceptor = interceptor;
        }

        public Builder matcher(ChatInterceptorMatcher matcher) {
            if (matcher == null) {
                throw new IllegalArgumentException("ChatInterceptorMatcher must not be null");
            }
            this.matcher = matcher;
            return this;
        }

        /** Sets request-chain order. Lower values execute first; all integer values are allowed. */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public ChatInterceptorRegistration build() {
            return new ChatInterceptorRegistration(this);
        }
    }
}
