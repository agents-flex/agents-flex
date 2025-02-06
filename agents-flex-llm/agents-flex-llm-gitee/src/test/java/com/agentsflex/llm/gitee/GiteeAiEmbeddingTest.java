package com.agentsflex.llm.gitee;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.VectorData;

public class GiteeAiEmbeddingTest {

    public static void main(String[] args) {
        GiteeAiLlmConfig config = new GiteeAiLlmConfig();
        config.setApiKey("your-api-key");

        GiteeAiLLM llm = new GiteeAiLLM(config);
        VectorData result = llm.embed(Document.of("你好"));
        System.out.println(result);
    }
}
