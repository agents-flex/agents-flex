package com.agentsflex.llm;

import com.agentsflex.functions.Function;
import com.agentsflex.prompt.Prompt;

import java.util.List;

public interface FunctionCalling {

    <R> R call(Prompt prompt, List<Function<R>> functions);

}
