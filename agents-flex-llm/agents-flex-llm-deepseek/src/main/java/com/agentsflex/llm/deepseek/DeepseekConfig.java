/*
 * @(#) DeepseekConfig Created by tony on 2025-01-17 15:26
 * @copyright © 2018-2024 博信数科科技. All rights reserved.
 */
package com.agentsflex.llm.deepseek;

import com.agentsflex.core.llm.LlmConfig;

/**
 * @author huangjf
 * @version : v1.0
 */
public class DeepseekConfig extends LlmConfig {

    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_EMBEDDING_MODEL = "";
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com";

    public DeepseekConfig() {
        setEndpoint(DEFAULT_ENDPOINT);
        setModel(DEFAULT_MODEL);
    }

    public DeepseekConfig(String model) {
        this();
        setModel(model);
    }
}
