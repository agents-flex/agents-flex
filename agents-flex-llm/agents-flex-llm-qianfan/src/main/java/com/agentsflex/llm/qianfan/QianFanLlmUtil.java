package com.agentsflex.llm.qianfan;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.functions.Function;
import com.agentsflex.core.llm.functions.Parameter;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class QianFanLlmUtil {
    private static final PromptFormat promptFormat = new DefaultPromptFormat() {
        @Override
        protected void buildFunctionJsonArray(List<Map<String, Object>> functionsJsonArray, List<Function> functions) {
            for (Function function : functions) {
                Map<String, Object> functionRoot = new HashMap<>();
                functionRoot.put("type", "function");

                Map<String, Object> functionObj = new HashMap<>();
                functionRoot.put("function", functionObj);

                functionObj.put("name", function.getName());
                functionObj.put("description", function.getDescription());


                Map<String, Object> parametersObj = new HashMap<>();
                functionObj.put("parameters", parametersObj);

                parametersObj.put("type", "object");

                Map<String, Object> propertiesObj = new HashMap<>();
                parametersObj.put("properties", propertiesObj);

                List<String> requiredProperties = new ArrayList<>();

                for (Parameter parameter : function.getParameters()) {
                    Map<String, Object> parameterObj = new HashMap<>();
                    parameterObj.put("type", parameter.getType());
                    parameterObj.put("description", parameter.getDescription());
                    if (parameter.getEnums().length > 0) {
                        parameterObj.put("enum", parameter.getEnums());
                    }
                    if (parameter.isRequired()) {
                        requiredProperties.add(parameter.getName());
                    }

                    propertiesObj.put(parameter.getName(), parameterObj);
                }

                if (!requiredProperties.isEmpty()) {
                    parametersObj.put("required", requiredProperties);
                }

                functionsJsonArray.add(functionRoot);
            }
        }
    };


    public static AiMessageParser getAiMessageParser(boolean isStream) {
        return DefaultAiMessageParser.getChatGPTMessageParser(isStream);
    }


    public static String promptToEmbeddingsPayload(Document text, EmbeddingOptions options, QianFanLlmConfig config) {
        List<String> documents = new ArrayList<>();
        documents.add(text.getContent());
        return Maps.of("model", options.getModelOrDefault(config.getEmbeddingModel()))
            .set("encoding_format", "float")
            .set("input", documents)
            .toJSON();
    }


    public static String promptToPayload(Prompt prompt, QianFanLlmConfig config, ChatOptions options, boolean withStream) {
        List<Message> messages = prompt.toMessages();
        Message message = CollectionUtil.lastItem(messages);
        return Maps.of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", promptFormat.toMessagesJsonObject(messages))
            .setIf(withStream, "stream", true)
            .setIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(message))
            .setIfContainsKey("tools", "tool_choice", MessageUtil.getToolChoice(message))
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotEmpty("stop", options.getStop())
            .setIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .setIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
            .toJSON();
    }
}



