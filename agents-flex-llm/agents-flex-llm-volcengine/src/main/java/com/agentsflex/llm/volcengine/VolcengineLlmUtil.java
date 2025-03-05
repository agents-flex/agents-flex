package com.agentsflex.llm.volcengine;

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.Maps;

import java.util.List;

public class VolcengineLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        return DefaultAiMessageParser.getChatGPTMessageParser(isStream);
    }

    public static String promptToPayload(Prompt prompt, VolcengineLlmConfig config, ChatOptions options, boolean withStream) {
        List<Message> messages = prompt.toMessages();
        Message message = CollectionUtil.lastItem(messages);

        String toolChoice = null;
        if (message instanceof HumanMessage) {
            toolChoice = ((HumanMessage) message).getToolChoice();
        }

        return Maps.of("model", config.getModel())
            .set("messages", promptFormat.toMessagesJsonObject(messages))
            .setIf(withStream, "stream", true)
            .setIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(message))
            .setIfContainsKey("tools", "tool_choice", toolChoice)
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotEmpty("stop", options.getStop())
            .setIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .setIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
            .toJSON();
    }
}
