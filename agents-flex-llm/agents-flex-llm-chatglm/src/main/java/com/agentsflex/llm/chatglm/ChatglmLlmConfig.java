
package com.agentsflex.llm.chatglm;

import com.agentsflex.llm.LlmConfig;

public class ChatglmLlmConfig extends LlmConfig {

	private static final String DEFAULT_MODEL = "glm-4";
	private static final String DEFAULT_ENDPOINT = "https://open.bigmodel.cn";

	public ChatglmLlmConfig() {
		setEndpoint(DEFAULT_ENDPOINT);
		setModel(DEFAULT_MODEL);
	}
}
