package com.agentsflex.llm;

import com.agentsflex.client.LlmClient;
import com.agentsflex.prompt.Prompt;

public interface Embeddings {

   LlmClient embeddings(Prompt prompt, EmbeddingsListener listener);

}
