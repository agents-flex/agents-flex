package com.agentsflex.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * McpClientManager unit test class
 * McpClientManager单元测试类
 */
class MDLMcpClientManagerTest {


    private McpClientManager mcpClientManager;

    // Test JSON configuration
    // 测试用JSON配置
    private static final String TEST_JSON_CONFIG = """
        {
              "mcpServers": {
                "mdl-mcp": {
                  "type": "streamablehttp",
                  "url": "https://mcp.mcd.cn",
                  "headers": {
                    "Authorization": "Bearer %s"
                  }
                }
              }
            }
        """.formatted(System.getenv("MDL_APIKEY"));

    @BeforeEach
    void setUp() {
        // 初始化Mockito mocks

        // Reset singleton instance before each test
        // 在每个测试前重置单例实例
        try {
            java.lang.reflect.Field instance = McpClientManager.class.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        mcpClientManager = McpClientManager.getInstance();
        mcpClientManager.registerFromJson(TEST_JSON_CONFIG);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mcpClientManager != null) {
            mcpClientManager.close();
        }
    }




    @Test
    @DisplayName("Test call tool - 测试工具调用")
    void testCallTool() {

        McpSyncClient mcpClient = mcpClientManager.getMcpClient("mdl-mcp");
        McpSchema.ListToolsResult listToolsResult = mcpClient.listTools();
        System.out.println("tools: " + listToolsResult.tools());


        McpSchema.CallToolResult callToolResult = mcpClient.callTool(new McpSchema.CallToolRequest("now-time-info", null));

        System.out.println("result: " + callToolResult.content().get(0));
    }
}
