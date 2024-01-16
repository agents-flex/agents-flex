package com.agentsflex.llm;

import com.agentsflex.prompt.Prompt;
import com.agentsflex.vector.VectorData;

public interface Embeddings {

   VectorData embeddings(Prompt prompt);

}
