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
package com.agentsflex.mcp.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class McpConfig {

    private List<InputSpec> inputs = Collections.emptyList();
    private Map<String, ServerSpec> mcpServers = Collections.emptyMap();

    public List<InputSpec> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputSpec> inputs) {
        this.inputs = inputs;
    }

    public Map<String, ServerSpec> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, ServerSpec> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public static class InputSpec {
        private String type;
        private String id;
        private String description;

        // getters
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class ServerSpec {
        private String transport = "stdio"; // 新增
        private String command;
        private List<String> args;
        private Map<String, String> env = Collections.emptyMap();
        private String url; // 新增

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
