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

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class McpClientManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static volatile McpClientManager INSTANCE;

    private final Map<String, McpClientDescriptor> descriptorRegistry = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthChecker;
    private final long healthCheckIntervalMs = 10_000;


    private static final String CONFIG_RESOURCE_PROPERTY = "mcp.config.resource";
    private static final String DEFAULT_CONFIG_RESOURCE = "mcp-server.json";

    private McpClientManager() {
        this.healthChecker = Executors.newSingleThreadScheduledExecutor(r ->
            new Thread(r, "mcp-health-checker")
        );
        this.healthChecker.scheduleAtFixedRate(
            this::performHealthCheck,
            healthCheckIntervalMs,
            healthCheckIntervalMs,
            TimeUnit.MILLISECONDS
        );
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "mcp-shutdown-hook"));

        autoLoadConfigFromResource();
    }

    private void autoLoadConfigFromResource() {
        String resourcePath = System.getProperty(CONFIG_RESOURCE_PROPERTY, DEFAULT_CONFIG_RESOURCE);
        try (InputStream is = McpClientManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                registerFromJson(json);
                log.info("Auto-loaded MCP configuration from: {}", resourcePath);
            } else {
                log.debug("MCP config resource not found (skipping auto-load): {}", resourcePath);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-load MCP config from resource: " + resourcePath, e);
        }
    }

    public static void reloadConfig() {
        McpClientManager manager = getInstance();
        // 先关闭所有现有 client
        manager.descriptorRegistry.values().forEach(McpClientDescriptor::close);
        manager.descriptorRegistry.clear();
        // 重新加载
        manager.autoLoadConfigFromResource();
    }

    public static McpClientManager getInstance() {
        if (INSTANCE == null) {
            synchronized (McpClientManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new McpClientManager();
                }
            }
        }
        return INSTANCE;
    }


    public void registerFromJson(String json) {
        McpRootConfig root = JSON.parseObject(json, McpRootConfig.class);
        registerFromConfig(root.getMcp());
    }

    public void registerFromFile(Path filePath) throws IOException {
        String json = Files.readString(filePath, StandardCharsets.UTF_8);
        registerFromJson(json);
    }

    public void registerFromResource(String resourcePath) {
        try (InputStream is = McpClientManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            registerFromJson(json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load MCP config from resource: " + resourcePath, e);
        }
    }

    private void registerFromConfig(McpRootConfig.McpConfig config) {
        for (Map.Entry<String, McpRootConfig.ServerSpec> entry : config.getServers().entrySet()) {
            String name = entry.getKey();
            McpRootConfig.ServerSpec spec = entry.getValue();

            if (descriptorRegistry.containsKey(name)) {
                log.warn("MCP client '{}' already registered, skipping.", name);
                continue;
            }

            Map<String, String> resolvedEnv = new HashMap<>();
            for (Map.Entry<String, String> envEntry : spec.getEnv().entrySet()) {
                String key = envEntry.getKey();
                String value = envEntry.getValue();
                if (value != null && value.startsWith("${input:") && value.endsWith("}")) {
                    String inputId = value.substring("${input:".length(), value.length() - 1);
                    value = System.getProperty("mcp.input." + inputId, "");
                }
                resolvedEnv.put(key, value);
            }

            McpClientDescriptor descriptor = new McpClientDescriptor(name, spec, resolvedEnv);
            descriptorRegistry.put(name, descriptor);
            log.info("Registered MCP client: {} (transport: {})", name, spec.getTransport());
        }
    }


    public McpSyncClient getClient(String name) {
        McpClientDescriptor desc = descriptorRegistry.get(name);
        if (desc == null) {
            throw new IllegalArgumentException("MCP client not found: " + name);
        }
        return desc.getClient();
    }

    public boolean isClientOnline(String name) {
        McpClientDescriptor desc = descriptorRegistry.get(name);
        return desc != null && desc.isAlive();
    }

    public void reconnect(String name) {
        McpClientDescriptor oldDesc = descriptorRegistry.get(name);
        if (oldDesc == null) return;

        oldDesc.close();
        McpClientDescriptor newDesc = new McpClientDescriptor(
            oldDesc.getName(), oldDesc.getSpec(), oldDesc.getResolvedEnv()
        );
        descriptorRegistry.put(name, newDesc);
        log.info("Reconnected MCP client: {}", name);
    }

    private void performHealthCheck() {
        for (McpClientDescriptor desc : descriptorRegistry.values()) {
            if (desc.isClosed()) continue;
            try {
                desc.pingIfNeeded();
            } catch (Exception e) {
                log.error("Health check error for client: " + desc.getName(), e);
            }
        }
    }

    @Override
    public void close() {
        healthChecker.shutdown();
        try {
            if (!healthChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                healthChecker.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthChecker.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (McpClientDescriptor desc : descriptorRegistry.values()) {
            try {
                desc.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client descriptor", e);
            }
        }
        descriptorRegistry.clear();
        log.info("McpClientManager closed.");
    }

}
