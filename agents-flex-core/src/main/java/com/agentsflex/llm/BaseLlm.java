package com.agentsflex.llm;

public abstract class BaseLlm<T extends LlmConfig> extends Llm{

    protected T config;

    public BaseLlm(T config) {
        this.config = config;
    }

    public T getConfig() {
        return config;
    }
}
