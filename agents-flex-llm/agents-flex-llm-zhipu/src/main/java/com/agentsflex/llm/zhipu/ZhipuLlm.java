package com.agentsflex.llm.zhipu;

import com.agentsflex.document.Document;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.ChatResponse;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.response.MessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.util.StringUtil;
import com.agentsflex.vector.VectorData;

import java.util.HashMap;
import java.util.Map;

public class ZhipuLlm extends BaseLlm<ZhipuLlmConfig> {

    private HttpClient httpClient = new HttpClient();

    public ZhipuLlm(ZhipuLlmConfig config) {
        super(config);
    }

    @Override
    public VectorData embeddings(Document document) {
        return null;
    }


    @SuppressWarnings("unchecked")
    @Override
    public <T extends ChatResponse<?>> T chat(Prompt<T> prompt) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ZhipuLlmUtil.createAuthorizationToken(config));

        String payload = ZhipuLlmUtil.promptToPayload(prompt, config);
        String responseString = httpClient.post("https://open.bigmodel.cn/api/paas/v4/chat/completions", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        if (prompt instanceof FunctionPrompt) {

        } else {
            AiMessage aiMessage = ZhipuLlmUtil.parseAiMessage(responseString);
            return (T) new MessageResponse(aiMessage);
        }

        return null;
    }

    @Override
    public void chatAsync(Prompt<?> prompt, ChatListener listener) {

    }
}
