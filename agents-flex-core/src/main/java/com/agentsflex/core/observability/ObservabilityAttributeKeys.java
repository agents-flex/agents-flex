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

import java.util.List;

/**
 * Agents-Flex 可观测数据中用于跨 Span 关联业务上下文的统一属性键。
 *
 * <p>OpenTelemetry 已定义的语义属性直接采用标准名称；Bot 和 Turn 没有对应标准语义，因此放在
 * {@code agentsflex} 命名空间下。应用和 Exporter 应复用这些常量，避免相同概念出现多种字符串名称。</p>
 */
public final class ObservabilityAttributeKeys {

    /** 提供大模型服务的厂商或平台名称。 */
    public static final AttributeKey<String> GEN_AI_PROVIDER_NAME =
        AttributeKey.stringKey("gen_ai.provider.name");

    /** 当前 GenAI 操作名称；ChatModel 调用统一使用 {@code chat}。 */
    public static final AttributeKey<String> GEN_AI_OPERATION_NAME =
        AttributeKey.stringKey("gen_ai.operation.name");

    /** 本次请求实际使用的模型名称。 */
    public static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
        AttributeKey.stringKey("gen_ai.request.model");

    /** 本次请求允许模型生成的最大 Token 数。 */
    public static final AttributeKey<Long> GEN_AI_REQUEST_MAX_TOKENS =
        AttributeKey.longKey("gen_ai.request.max_tokens");

    /** 本次请求的温度参数。 */
    public static final AttributeKey<Double> GEN_AI_REQUEST_TEMPERATURE =
        AttributeKey.doubleKey("gen_ai.request.temperature");

    /** 本次请求的 Top-P 参数。 */
    public static final AttributeKey<Double> GEN_AI_REQUEST_TOP_P =
        AttributeKey.doubleKey("gen_ai.request.top_p");

    /** 本次请求的 Top-K 参数。 */
    public static final AttributeKey<Long> GEN_AI_REQUEST_TOP_K =
        AttributeKey.longKey("gen_ai.request.top_k");

    /** 本次请求配置的停止序列。 */
    public static final AttributeKey<List<String>> GEN_AI_REQUEST_STOP_SEQUENCES =
        AttributeKey.stringArrayKey("gen_ai.request.stop_sequences");

    /** 模型实际消耗的输入 Token 数。 */
    public static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS =
        AttributeKey.longKey("gen_ai.usage.input_tokens");

    /** 模型实际消耗的输出 Token 数。 */
    public static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS =
        AttributeKey.longKey("gen_ai.usage.output_tokens");

    /** 模型返回的结束原因列表。 */
    public static final AttributeKey<List<String>> GEN_AI_RESPONSE_FINISH_REASONS =
        AttributeKey.stringArrayKey("gen_ai.response.finish_reasons");

    /** GenAI Token 指标的数据类型，取值为 {@code input} 或 {@code output}。 */
    public static final AttributeKey<String> GEN_AI_TOKEN_TYPE =
        AttributeKey.stringKey("gen_ai.token.type");

    /** 被调用的工具名称。 */
    public static final AttributeKey<String> GEN_AI_TOOL_NAME =
        AttributeKey.stringKey("gen_ai.tool.name");

    /** 开启内容采集后保存的脱敏模型响应正文，属于 Agents-Flex 扩展属性。 */
    public static final AttributeKey<String> GEN_AI_RESPONSE_CONTENT =
        AttributeKey.stringKey("agentsflex.gen_ai.response.content");

    /** 开启内容采集后保存的脱敏工具参数，属于 Agents-Flex 扩展属性。 */
    public static final AttributeKey<String> GEN_AI_TOOL_ARGUMENTS =
        AttributeKey.stringKey("agentsflex.gen_ai.tool.arguments");

    /** 开启内容采集后保存的脱敏工具结果，属于 Agents-Flex 扩展属性。 */
    public static final AttributeKey<String> GEN_AI_TOOL_RESULT =
        AttributeKey.stringKey("agentsflex.gen_ai.tool.result");

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
