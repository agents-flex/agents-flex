package com.agentsflex.llm.qianfan;

import com.agentsflex.core.model.chat.ChatConfig;

public class QianFanChatConfig extends ChatConfig {
    private static final String DEFAULT_MODEL = "ernie-3.5-8k";
    private static final String DEFAULT_ENDPOINT = "https://qianfan.baidubce.com";
    private static final String DEFAULT_REQUEST_PATH = "/v2/chat/completions";

    public QianFanChatConfig() {
        setEndpoint(DEFAULT_ENDPOINT);
        setRequestPath(DEFAULT_REQUEST_PATH);
        setModel(DEFAULT_MODEL);
    }

    public QianFanChatConfig(String apikey) {
        setEndpoint(DEFAULT_ENDPOINT);
        setRequestPath(DEFAULT_REQUEST_PATH);
        setModel(DEFAULT_MODEL);
        setApiKey(apikey);
    }

}
