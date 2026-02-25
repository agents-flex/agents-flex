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

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Map;

public class StdioTransportFactory implements McpTransportFactory {

    @Override
    public CloseableTransport create(McpConfig.ServerSpec spec, Map<String, String> resolvedEnv) {
        try {
            ServerParameters parameters = ServerParameters.builder(spec.getCommand())
                .args(spec.getArgs())
                .build();

            StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());

            return new CloseableTransport() {
                @Override
                public McpClientTransport getTransport() {
                    return transport;
                }

                @Override
                public void close() {
                    try {
                        transport.close();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to start stdio process", e);
        }
    }
}
