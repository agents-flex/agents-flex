/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.llm.deepseek;

import com.agentsflex.core.model.chat.ChatConfig;

/**
 * @author huangjf
 * @version : v1.0
 */
public class DeepseekConfig extends ChatConfig {

    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com";
    private static final String DEFAULT_REQUEST_PATH = "/chat/completions";

    public DeepseekConfig() {
        setEndpoint(DEFAULT_ENDPOINT);
        setRequestPath(DEFAULT_REQUEST_PATH);
        setModel(DEFAULT_MODEL);
    }

}
