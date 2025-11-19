/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.spark;

import com.agentsflex.core.model.chat.ChatConfig;

public class SparkChatConfig extends ChatConfig {

    public String getAppId() {
        return properties != null ? properties.get("appId") : null;
    }

    public void setAppId(String appId) {
        this.addProperty("appId", appId);
    }

    public String getApiSecret() {
        return properties != null ? properties.get("apiSecret") : null;
    }

    public void setApiSecret(String apiSecret) {
        addProperty("apiSecret", apiSecret);
    }

    public String getVersion() {
        return properties != null ? properties.get("version") : null;
    }

    public void setVersion(String version) {
        addProperty("version", version);
    }

}
