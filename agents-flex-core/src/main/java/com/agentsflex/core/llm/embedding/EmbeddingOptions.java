package com.agentsflex.core.llm.embedding;

import com.agentsflex.core.util.StringUtil;

public class EmbeddingOptions {
    public static final EmbeddingOptions DEFAULT = new EmbeddingOptions(){
        @Override
        public void setModel(String model) {
            throw new IllegalStateException("Can not set modal to the default instance.");
        }
    };

    private String model;

    public String getModel() {
        return model;
    }

    public String getModelOrDefault(String defaultModel) {
        return StringUtil.noText(model) ? defaultModel : model;
    }

    public void setModel(String model) {
        this.model = model;
    }


    @Override
    public String toString() {
        return "EmbeddingOptions{" +
            "model='" + model + '\'' +
            '}';
    }
}
