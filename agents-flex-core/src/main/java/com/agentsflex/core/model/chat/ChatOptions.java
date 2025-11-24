/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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

import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;

import java.util.List;
import java.util.Map;

/**
 * 聊天选项配置类，用于控制大语言模型（LLM）的生成行为。
 * 支持 Builder 模式，便于链式调用。
 * 注意：不同模型厂商对参数的支持和默认值可能不同。
 */
public class ChatOptions {


    /**
     * 指定使用的大模型名称。
     * 例如："gpt-4", "qwen-max", "claude-3-sonnet" 等。
     * 如果未设置，将使用客户端默认模型。
     */
    private String model;

    /**
     * 随机种子（Seed），用于控制生成结果的可重复性。
     * 当 seed 相同时，相同输入将产生相同输出（前提是其他参数也一致）。
     * 注意：并非所有模型都支持 seed 参数。
     */
    private String seed;

    /**
     * 温度（Temperature）控制输出的随机性。
     * <ul>
     *   <li>值越低（如 0.1~0.3）：输出更确定、稳定、可重复，适合事实性任务（如 RAG、结构化输出）</li>
     *   <li>值越高（如 0.7~1.0）：输出更多样、有创意，但可能不稳定或偏离事实</li>
     * </ul>
     * 推荐值：
     * <ul>
     *   <li>文档处理、路由、工具调用：0.1 ~ 0.3</li>
     *   <li>问答、摘要：0.2 ~ 0.5</li>
     *   <li>创意写作：0.7 ~ 1.0</li>
     * </ul>
     * 默认值：0.5f
     */
    private Float temperature = 0.5f;

    /**
     * Top-p（也称 nucleus sampling）控制生成时考虑的概率质量。
     * 模型从累积概率不超过 p 的最小词集中采样。
     * - 值为 1.0 表示考虑所有词（等同于无 top-p 限制）
     * - 值为 0.9 表示只考虑累积概率达 90% 的词
     * 注意：temperature 和 top_p 不应同时调整，通常只用其一。
     */
    private Float topP;

    /**
     * Top-k 控制生成时考虑的最高概率词的数量。
     * 模型仅从 top-k 个最可能的词中采样。
     * - 值为 50 表示只考虑概率最高的 50 个词
     * - 值越小，输出越确定；值越大，输出越多样
     * 注意：与 top_p 类似，通常不与 temperature 同时使用。
     */
    private Integer topK;

    /**
     * 生成内容的最大 token 数量（不包括输入 prompt）。
     * 用于限制响应长度，防止生成过长内容。
     * 注意：不同模型有不同上限，超过将被截断或报错。
     */
    private Integer maxTokens;

    /**
     * 停止序列（Stop Sequences），当生成内容包含这些字符串时立即停止。
     * 例如：设置为 ["\n", "。"] 可在句末或换行时停止。
     * 适用于需要精确控制输出长度的场景。
     */
    private List<String> stop;

    /**
     * 是否启用“思考模式”（Thinking Mode）。
     * 适用于支持该特性的模型（如 Qwen3），开启后模型会显式输出推理过程。
     * 默认为 null（由模型决定）。
     */
    private Boolean thinkingEnabled;

    /**
     * 额外的模型参数，用于传递模型特有或未明确暴露的配置。
     * 例如：{"response_format": "json", "presence_penalty": 0.5}
     * 使用 addExtra() 方法可方便地添加单个参数。
     */
    private Map<String, Object> extra;


    /**
     * 是否为流式请求。
     * 这个不允许用户设置，由 Framework 自动设置（用户设置也可能被修改）。
     * 用户调用 chat 或者 chatStream 方法时，会自动设置这个字段。
     */
    private boolean streaming;

    // ===== 构造函数 =====
    public ChatOptions() {
    }

    private ChatOptions(Builder builder) {
        this.model = builder.model;
        this.seed = builder.seed;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.topK = builder.topK;
        this.maxTokens = builder.maxTokens;
        this.stop = builder.stop;
        this.thinkingEnabled = builder.thinkingEnabled;
        this.extra = builder.extra;
    }

    // ===== Getter / Setter =====

    public String getModel() {
        return model;
    }

    public String getModelOrDefault(String defaultModel) {
        return StringUtil.hasText(model) ? model : defaultModel;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSeed() {
        return seed;
    }

    public void setSeed(String seed) {
        this.seed = seed;
    }

    public Float getTemperature() {
        return temperature;
    }

    public void setTemperature(Float temperature) {
        if (temperature < 0) {
            throw new IllegalArgumentException("temperature must be greater than 0");
        }
        this.temperature = temperature;
    }

    public Float getTopP() {
        return topP;
    }

    public void setTopP(Float topP) {
        if (topP < 0 || topP > 1) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
        this.topP = topP;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        if (topK < 0) {
            throw new IllegalArgumentException("topK must be greater than 0");
        }
        this.topK = topK;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be greater than 0");
        }
        this.maxTokens = maxTokens;
    }

    public List<String> getStop() {
        return stop;
    }

    public void setStop(List<String> stop) {
        this.stop = stop;
    }

    public Boolean getThinkingEnabled() {
        return thinkingEnabled;
    }

    public Boolean getThinkingEnabledOrDefault(Boolean defaultValue) {
        return thinkingEnabled != null ? thinkingEnabled : defaultValue;
    }

    public void setThinkingEnabled(Boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }


    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    /**
     * 添加一个额外参数到 extra 映射中。
     *
     * @param key   参数名
     * @param value 参数值
     */
    public void addExtra(String key, Object value) {
        if (extra == null) {
            extra = Maps.of(key, value);
        } else {
            extra.put(key, value);
        }
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    // ===== Builder 模式 =====

    /**
     * 创建 ChatOptions 的 Builder 实例。
     *
     * @return 新的 Builder 对象
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * ChatOptions 的构建器类，支持链式调用。
     */
    public static final class Builder {
        private String model;
        private String seed;
        private Float temperature = 0.5f;
        private Float topP;
        private Integer topK;
        private Integer maxTokens;
        private List<String> stop;
        private Boolean thinkingEnabled;
        private Map<String, Object> extra;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder seed(String seed) {
            this.seed = seed;
            return this;
        }

        public Builder temperature(Float temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Float topP) {
            this.topP = topP;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        public Builder thinkingEnabled(Boolean thinkingEnabled) {
            this.thinkingEnabled = thinkingEnabled;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        public Builder addExtra(String key, Object value) {
            if (this.extra == null) {
                this.extra = Maps.of(key, value);
            } else {
                this.extra.put(key, value);
            }
            return this;
        }

        /**
         * 构建并返回 ChatOptions 实例。
         *
         * @return 配置完成的 ChatOptions 对象
         */
        public ChatOptions build() {
            return new ChatOptions(this);
        }
    }
}
