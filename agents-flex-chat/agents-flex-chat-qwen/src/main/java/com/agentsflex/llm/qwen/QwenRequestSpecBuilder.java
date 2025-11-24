package com.agentsflex.llm.qwen;

import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.Maps;

public class QwenRequestSpecBuilder extends OpenAIChatRequestSpecBuilder {

    @Override
    protected Maps buildBaseParamsOfRequestBody(Prompt prompt, ChatOptions options, ChatConfig config) {
        Maps params = super.buildBaseParamsOfRequestBody(prompt, options, config);
        if (options instanceof QwenChatOptions) {
            QwenChatOptions op = (QwenChatOptions) options;
            params.setIf(CollectionUtil.hasItems(op.getModalities()), "modalities", op.getModalities());
            params.setIf(op.getPresencePenalty() != null, "presence_penalty", op.getPresencePenalty());
            params.setIf(op.getResponseFormat() != null, "response_format", op.getResponseFormat());
            params.setIf(op.getN() != null, "n", op.getN());
            params.setIf(op.getParallelToolCalls() != null, "parallel_tool_calls", op.getParallelToolCalls());
            params.setIf(op.getTranslationOptions() != null, "translation_options", op.getTranslationOptions());
            params.setIf(op.getEnableSearch() != null, "enable_search", op.getEnableSearch());
            params.setIf(op.getEnableSearch() != null && op.getEnableSearch() && op.getSearchOptions() != null, "search_options", op.getSearchOptions());
            params.setIf(op.getThinkingEnabled() != null, "enable_thinking", op.getThinkingEnabled());
            params.setIf(op.getThinkingEnabled() != null && op.getThinkingEnabled() && op.getThinkingBudget() != null, "thinking_budget", op.getThinkingBudget());
        }
        return params;
    }
}
