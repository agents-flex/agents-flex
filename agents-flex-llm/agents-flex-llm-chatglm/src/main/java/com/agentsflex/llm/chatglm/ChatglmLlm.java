package com.agentsflex.llm.chatglm;

import com.agentsflex.document.Document;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.ChatResponse;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.response.MessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.Message;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.util.StringUtil;
import com.agentsflex.store.VectorData;

import java.util.HashMap;
import java.util.Map;

public class ChatglmLlm extends BaseLlm<ChatglmLlmConfig> {

    private HttpClient httpClient = new HttpClient();

    public ChatglmLlm(ChatglmLlmConfig config) {
        super(config);
    }

    @Override
    public VectorData embeddings(Document document) {
        return null;
    }


    @SuppressWarnings("unchecked")
    @Override
    public <R extends ChatResponse<M>, M extends Message> R chat(Prompt<M> prompt) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String payload = ChatglmLlmUtil.promptToPayload(prompt, config);
        String responseString = httpClient.post("https://open.bigmodel.cn/api/paas/v4/chat/completions", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        if (prompt instanceof FunctionPrompt) {

        } else {
            AiMessage aiMessage = ChatglmLlmUtil.parseAiMessage(responseString);
            return (R) new MessageResponse(aiMessage);
        }

        return null;
    }

    @Override
    public <R extends ChatResponse<M>, M extends Message> void chatAsync(Prompt<M> prompt, ChatListener<R, M> listener) {

    }
}
