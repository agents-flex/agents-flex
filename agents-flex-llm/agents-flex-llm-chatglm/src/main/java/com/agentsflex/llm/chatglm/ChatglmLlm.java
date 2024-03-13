package com.agentsflex.llm.chatglm;

import com.agentsflex.document.Document;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.MessageListener;
import com.agentsflex.llm.MessageResponse;
import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.SseClient;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.llm.response.FunctionMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.FunctionMessage;
import com.agentsflex.message.Message;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.store.VectorData;
import com.agentsflex.util.Maps;
import com.agentsflex.util.StringUtil;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.Map;

public class ChatglmLlm extends BaseLlm<ChatglmLlmConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = ChatglmLlmUtil.getAiMessageParser();
    public FunctionMessageParser functionMessageParser = ChatglmLlmUtil.getFunctionMessageParser();


    public ChatglmLlm(ChatglmLlmConfig config) {
        super(config);
    }

    /**
     * 文档参考：https://open.bigmodel.cn/dev/api#text_embedding
     *
     * @param document 文档内容
     * @return 返回向量数据
     */
    @Override
    public VectorData embeddings(Document document) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String payload = Maps.of("model", "embedding-2").put("input", document.getContent()).toJSON();
        String responseString = httpClient.post("https://open.bigmodel.cn/api/paas/v4/embeddings", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        vectorData.setVector(JSONPath.read(responseString, "$.data[0].embedding", double[].class));

        return vectorData;
    }


    @Override
    public <R extends MessageResponse<M>, M extends Message> R chat(Prompt<M> prompt) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String payload = ChatglmLlmUtil.promptToPayload(prompt, config, false);
        String responseString = httpClient.post("https://open.bigmodel.cn/api/paas/v4/chat/completions", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        if (prompt instanceof FunctionPrompt) {
            FunctionMessage functionMessage = functionMessageParser.parse(responseString);
            return (R) new FunctionMessageResponse(((FunctionPrompt) prompt).getFunctions(), functionMessage);
        } else {
            AiMessage aiMessage = aiMessageParser.parse(responseString);
            return (R) new AiMessageResponse(aiMessage);
        }
    }


    @Override
    public <R extends MessageResponse<M>, M extends Message> void chatAsync(Prompt<M> prompt, MessageListener<R, M> listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String payload = ChatglmLlmUtil.promptToPayload(prompt, config, true);

        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser, functionMessageParser);
        llmClient.start("https://open.bigmodel.cn/api/paas/v4/chat/completions", headers, payload, clientListener, config);
    }


}
