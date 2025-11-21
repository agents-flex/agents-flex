package com.agentsflex.core.model.chat.tool;

public interface ToolChain {
    Object proceed(ToolContext context) throws Exception;
}
