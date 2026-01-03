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
package com.agentsflex.core.mcp;

import com.agentsflex.core.model.chat.tool.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class McpClientDescriptor {
    private static final Logger log = LoggerFactory.getLogger(McpClientDescriptor.class);

    private final String name;
    private final McpConfig.ServerSpec spec;
    private final Map<String, String> resolvedEnv;

    private volatile McpSyncClient client;
    private volatile CloseableTransport managedTransport;
    private volatile boolean closed = false;
    private final AtomicBoolean initializing = new AtomicBoolean(false);

    private volatile boolean alive = false;
    private volatile Instant lastPingTime = Instant.EPOCH;
    private static final long MIN_PING_INTERVAL_MS = 5_000;

    public McpClientDescriptor(String name, McpConfig.ServerSpec spec, Map<String, String> resolvedEnv) {
        this.name = name;
        this.spec = spec;
        this.resolvedEnv = new HashMap<>(resolvedEnv);
    }

    synchronized McpSyncClient getClient() {
        if (closed) {
            throw new IllegalStateException("MCP client closed: " + name);
        }
        if (client == null) {
            initialize();
        }
        return client;
    }


    public Tool getMcpTool(String toolName) {
        McpSyncClient client = getClient();
        McpSchema.ListToolsResult listToolsResult = client.listTools();
        for (McpSchema.Tool tool : listToolsResult.tools()) {
            if (tool.name().equals(toolName)) {
                return new McpTool(getClient(), tool);
            }
        }

        return null;
    }

    private synchronized void initialize() {
        if (client != null || closed) return;
        if (!initializing.compareAndSet(false, true)) {
            while (client == null && !closed) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Initialization interrupted", e);
                }
            }
            return;
        }

        try {
            McpTransportFactory factory = getTransportFactory(spec.getTransport());
            CloseableTransport transport = factory.create(spec, resolvedEnv);
            this.managedTransport = transport;

            McpSyncClient c = McpClient.sync(transport.getTransport())
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build();

            c.initialize();
            this.client = c;
            this.alive = true;
            log.info("MCP client initialized: {}", name);

        } catch (Exception e) {
            String errorMsg = "Failed to initialize MCP client: " + name + ", error: " + e.getMessage();
            log.error(errorMsg, e);
            if (managedTransport != null) {
                try {
                    managedTransport.close();
                } catch (Exception closeEx) {
                    log.warn("Error closing transport during init failure", closeEx);
                }
            }
            throw new RuntimeException(errorMsg, e);
        } finally {
            initializing.set(false);
            notifyAll();
        }
    }

    boolean pingIfNeeded() {
        if (closed || client == null) {
            alive = false;
            return false;
        }

        long now = System.currentTimeMillis();
        if ((now - lastPingTime.toEpochMilli()) < MIN_PING_INTERVAL_MS) {
            return alive;
        }

        try {
            client.ping();
            alive = true;
        } catch (Exception e) {
            alive = false;
            String msg = String.format("Ping failed for MCP client '%s': %s", name, e.getMessage());
            log.debug(msg);
        } finally {
            lastPingTime = Instant.now();
        }
        return alive;
    }

    boolean isAlive() {
        return alive && !closed && client != null;
    }

    boolean isClosed() {
        return closed;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;

        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
            client = null;
        }

        if (managedTransport != null) {
            try {
                managedTransport.close();
            } catch (Exception e) {
                log.warn("Error closing transport for '{}'", name, e);
            }
            managedTransport = null;
        }

        alive = false;
        log.info("MCP client closed: {}", name);
    }

    private McpTransportFactory getTransportFactory(String transportType) {
        switch (transportType.toLowerCase()) {
            case "stdio":
                return new StdioTransportFactory();
            case "http-sse":
                return new HttpSseTransportFactory();
            case "http-stream":
                return new HttpStreamTransportFactory();
            default:
                throw new IllegalArgumentException("Unsupported transport: " + transportType);
        }
    }

    public String getName() {
        return name;
    }

    public McpConfig.ServerSpec getSpec() {
        return spec;
    }

    public Map<String, String> getResolvedEnv() {
        return resolvedEnv;
    }

    public void setClient(McpSyncClient client) {
        this.client = client;
    }

    public CloseableTransport getManagedTransport() {
        return managedTransport;
    }

    public void setManagedTransport(CloseableTransport managedTransport) {
        this.managedTransport = managedTransport;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public AtomicBoolean getInitializing() {
        return initializing;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public Instant getLastPingTime() {
        return lastPingTime;
    }

    public void setLastPingTime(Instant lastPingTime) {
        this.lastPingTime = lastPingTime;
    }
}
