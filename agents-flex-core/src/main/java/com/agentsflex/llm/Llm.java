package com.agentsflex.llm;

import com.agentsflex.client.LlmClient;
import com.agentsflex.prompt.Prompt;

public abstract class Llm  implements Embeddings{


    public abstract LlmClient chat(Prompt prompt, ChatListener listener);

}
