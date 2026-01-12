package com.agentsflex.llm.ollama;

import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;

public class OllamaRequestSpecBuilder extends OpenAIChatRequestSpecBuilder {
    protected Maps buildBaseParamsOfRequestBody(Prompt prompt, ChatOptions options, ChatConfig config) {
        Maps params = super.buildBaseParamsOfRequestBody(prompt, options, config);
        params.setIf(!options.isStreaming(), "stream", false);

        // 支持思考
        if (config.getSupportThinking()) {
            params.setIf(options.getThinkingEnabled() != null, "think", options.getThinkingEnabled());
        }

        return params;
    }
}
