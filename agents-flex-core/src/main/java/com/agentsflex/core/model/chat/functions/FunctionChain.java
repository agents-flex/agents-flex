package com.agentsflex.core.model.chat.functions;

public interface FunctionChain {
    Object proceed(FunctionContext context) throws Exception;
}
