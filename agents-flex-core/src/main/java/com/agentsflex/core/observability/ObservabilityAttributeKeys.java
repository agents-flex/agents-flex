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
package com.agentsflex.core.observability;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Agents-Flex 可观测数据中用于跨 Span 关联业务上下文的统一属性键。
 *
 * <p>OpenTelemetry 已定义的语义属性直接采用标准名称；Bot 和 Turn 没有对应标准语义，因此放在
 * {@code agentsflex} 命名空间下。应用和 Exporter 应复用这些常量，避免相同概念出现多种字符串名称。</p>
 */
public final class ObservabilityAttributeKeys {

    /** 宿主系统业务 Bot 的稳定 ID。 */
    public static final AttributeKey<String> BOT_ID = AttributeKey.stringKey("agentsflex.bot.id");

    /** 一次会话中单轮用户交互的稳定 ID。 */
    public static final AttributeKey<String> TURN_ID = AttributeKey.stringKey("agentsflex.turn.id");

    /** OpenTelemetry GenAI 语义约定中的会话 ID。 */
    public static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey("gen_ai.conversation.id");

    /** OpenTelemetry 最终用户语义属性，用于关联宿主系统账号。 */
    public static final AttributeKey<String> ACCOUNT_ID = AttributeKey.stringKey("enduser.id");

    private ObservabilityAttributeKeys() {
    }
}
