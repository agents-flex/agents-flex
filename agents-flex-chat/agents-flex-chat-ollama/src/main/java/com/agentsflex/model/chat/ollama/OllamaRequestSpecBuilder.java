package com.agentsflex.model.chat.ollama;

import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;

public class OllamaRequestSpecBuilder extends OpenAIChatRequestSpecBuilder {
    protected Maps buildBaseParamsOfRequestBody(Prompt prompt, ChatOptions options,
                                                BaseChatConfig config, boolean streaming) {
        Maps params = super.buildBaseParamsOfRequestBody(prompt, options, config, streaming);
        params.setIf(!streaming, "stream", false);

        // 支持思考
        if (config.isSupportThinking()) {
            params.setIf(options.getThinkingEnabled() != null, "thinking", options.getThinkingEnabled());
        }

        return params;
    }
}
