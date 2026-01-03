package com.agentsflex.mcp.client;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * McpClientManager unit test class
 * McpClientManager单元测试类
 */
class McpClientManagerTest {

    @Mock
    private McpClientDescriptor mockDescriptor;

    @Mock
    private McpSyncClient mockClient;

    @Mock
    private Tool mockTool;

    private McpClientManager mcpClientManager;
    private AutoCloseable mockitoMocks;

    // Test JSON configuration
    // 测试用JSON配置
    private static final String TEST_JSON_CONFIG = """
        {
            "mcpServers": {
              "test-server": {
                "transport": "stdio",
                "env": {
                  "TEST_VAR": "test_value",
                  "INPUT_VAR": "${input:test_input}"
                }
              }
          }
        }
        """;

    @BeforeEach
    void setUp() {
        // 初始化Mockito mocks
        mockitoMocks = MockitoAnnotations.openMocks(this);

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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mcpClientManager != null) {
            mcpClientManager.close();
        }
        // 关闭Mockito mocks
        if (mockitoMocks != null) {
            mockitoMocks.close();
        }
    }

    @Test
    @DisplayName("Test singleton pattern - 单例模式测试")
    void testSingletonPattern() {
        McpClientManager instance1 = McpClientManager.getInstance();
        McpClientManager instance2 = McpClientManager.getInstance();

        assertSame(instance1, instance2, "Should return same instance for singleton pattern");
        assertNotNull(instance1, "Instance should not be null");
    }

    @Test
    @DisplayName("Test registerFromJson - JSON配置注册测试")
    void testRegisterFromJson() {
        // Mock descriptor behavior
        // 模拟描述符行为
        when(mockDescriptor.getName()).thenReturn("test-server");
        when(mockDescriptor.isAlive()).thenReturn(true);
        when(mockDescriptor.getClient()).thenReturn(mockClient);
        when(mockDescriptor.getMcpTool("test-tool")).thenReturn(mockTool);

        // Use reflection to replace descriptor in registry
        // 使用反射替换注册表中的描述符
        try {
            java.lang.reflect.Field registryField = McpClientManager.class.getDeclaredField("descriptorRegistry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, McpClientDescriptor> registry =
                (java.util.Map<String, McpClientDescriptor>) registryField.get(mcpClientManager);

            registry.put("test-server", mockDescriptor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Test client retrieval
        // 测试客户端获取
        McpSyncClient client = mcpClientManager.getMcpClient("test-server");
        assertNotNull(client, "Client should not be null");
        assertSame(mockClient, client, "Should return the mocked client");

        // Test tool retrieval
        // 测试工具获取
        Tool tool = mcpClientManager.getMcpTool("test-server", "test-tool");
        assertNotNull(tool, "Tool should not be null");
        assertSame(mockTool, tool, "Should return the mocked tool");

        // Test online status
        // 测试在线状态
        assertTrue(mcpClientManager.isClientOnline("test-server"), "Client should be online");
    }

    @Test
    @DisplayName("Test getClient with non-existent client - 获取不存在客户端测试")
    void testGetMcpClientNonExistent() {
        assertThrows(IllegalArgumentException.class, () -> {
            mcpClientManager.getMcpClient("non-existent-server");
        }, "Should throw IllegalArgumentException for non-existent client");
    }

    @Test
    @DisplayName("Test getMcpTool with non-existent client - 获取不存在客户端工具测试")
    void testGetMcpToolNonExistent() {
        assertThrows(IllegalArgumentException.class, () -> {
            mcpClientManager.getMcpTool("non-existent-server", "test-tool");
        }, "Should throw IllegalArgumentException for non-existent client");
    }

    @Test
    @DisplayName("Test isClientOnline with non-existent client - 检查不存在客户端在线状态测试")
    void testIsClientOnlineNonExistent() {
        assertFalse(mcpClientManager.isClientOnline("non-existent-server"),
            "Should return false for non-existent client");
    }

    @Test
    @DisplayName("Test registerFromFile - 文件配置注册测试")
    void testRegisterFromFile() throws IOException {
        // Create a temporary file with test config
        // 创建包含测试配置的临时文件
        Path tempFile = Files.createTempFile("test-config", ".json");
        Files.write(tempFile, TEST_JSON_CONFIG.getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> {
            mcpClientManager.registerFromFile(tempFile);
        }, "Should not throw exception when registering from file");

        // Clean up
        // 清理
        Files.deleteIfExists(tempFile);
    }

    @Test
    @DisplayName("Test registerFromFile IOException - 文件配置注册IO异常测试")
    void testRegisterFromFileIOException() {
        Path nonExistentPath = Path.of("/non/existent/path.json");

        assertThrows(IOException.class, () -> {
            mcpClientManager.registerFromFile(nonExistentPath);
        }, "Should throw IOException for non-existent file");
    }

    @Test
    @DisplayName("Test registerFromResource - 资源配置注册测试")
    void testRegisterFromResource() {
        String resourcePath = "mcp-servers.json";
        assertDoesNotThrow(() -> {
            mcpClientManager.registerFromResource(resourcePath);
        }, "Should not throw exception when registering from resource");
    }

    @Test
    @DisplayName("Test registerFromResource with non-existent resource - 注册不存在资源测试")
    void testRegisterFromResourceNonExistent() {
        String resourcePath = "non-existent-config.json";

        try (MockedStatic<ClassLoader> classLoaderMock = Mockito.mockStatic(ClassLoader.class)) {
            ClassLoader mockClassLoader = mock(ClassLoader.class);

            classLoaderMock.when(() -> McpClientManager.class.getClassLoader()).thenReturn(mockClassLoader);
            when(mockClassLoader.getResourceAsStream(resourcePath)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () -> {
                mcpClientManager.registerFromResource(resourcePath);
            }, "Should throw IllegalArgumentException for non-existent resource");
        }
    }

    @Test
    @DisplayName("Test reconnect functionality - 重新连接功能测试")
    void testReconnect() {
        // First register a descriptor
        // 首先注册一个描述符
        try {
            java.lang.reflect.Field registryField = McpClientManager.class.getDeclaredField("descriptorRegistry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, McpClientDescriptor> registry =
                (java.util.Map<String, McpClientDescriptor>) registryField.get(mcpClientManager);

            registry.put("test-server", mockDescriptor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Mock close behavior
        // 模拟关闭行为
        doNothing().when(mockDescriptor).close();
        when(mockDescriptor.getName()).thenReturn("test-server");
        when(mockDescriptor.getSpec()).thenReturn(new McpConfig().getMcpServers().get("test-server"));
        when(mockDescriptor.getResolvedEnv()).thenReturn(new HashMap<>());

        assertDoesNotThrow(() -> {
            mcpClientManager.reconnect("test-server");
        }, "Reconnect should not throw exception");

        // Verify close was called
        // 验证关闭被调用
        verify(mockDescriptor, times(1)).close();
    }

    @Test
    @DisplayName("Test reconnect with non-existent client - 重新连接不存在客户端测试")
    void testReconnectNonExistent() {
        assertDoesNotThrow(() -> {
            mcpClientManager.reconnect("non-existent-server");
        }, "Reconnect should not throw exception for non-existent client");
    }

    @Test
    @DisplayName("Test reloadConfig - 重新加载配置测试")
    void testReloadConfig() throws IllegalAccessException, NoSuchFieldException {
        // Mock the autoLoadConfigFromResource method
        // 模拟autoLoadConfigFromResource方法
        try (MockedStatic<McpClientManager> managerMock = Mockito.mockStatic(McpClientManager.class,
            Mockito.CALLS_REAL_METHODS)) {

            // Add a descriptor first
            // 首先添加一个描述符
            java.lang.reflect.Field registryField = McpClientManager.class.getDeclaredField("descriptorRegistry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, McpClientDescriptor> registry =
                (java.util.Map<String, McpClientDescriptor>) registryField.get(mcpClientManager);

            registry.put("test-server", mockDescriptor);

            // Mock close behavior
            // 模拟关闭行为
            doNothing().when(mockDescriptor).close();

            assertDoesNotThrow(() -> {
                McpClientManager.reloadConfig();
            }, "Reload config should not throw exception");

            // Verify that existing descriptors were closed
            // 验证现有描述符被关闭
            verify(mockDescriptor, times(1)).close();
        }
    }

    @Test
    @DisplayName("Test close functionality - 关闭功能测试")
    void testClose() {
        assertDoesNotThrow(() -> {
            mcpClientManager.close();
        }, "Close should not throw exception");

        // Verify that the manager can be closed multiple times safely
        // 验证管理器可以安全地多次关闭
        assertDoesNotThrow(() -> {
            mcpClientManager.close();
        }, "Second close should not throw exception");
    }

    @Test
    @DisplayName("Test environment variable resolution - 环境变量解析测试")
    void testEnvironmentVariableResolution() {
        // Test the registerFromConfig method with environment variable resolution
        // 测试带有环境变量解析的registerFromConfig方法
        String jsonWithInputVar = """
            {
                "mcpServers": {
                  "test-server": {
                    "transport": "stdio",
                    "env": {
                      "INPUT_VAR": "${input:test_input}",
                      "NORMAL_VAR": "normal_value"
                    }
                  }
                }
            }
            """;

        // Set system property for input resolution
        // 设置输入解析的系统属性
        System.setProperty("mcp.input.test_input", "resolved_value");

        try {
            mcpClientManager.registerFromJson(jsonWithInputVar);
        } finally {
            // Clean up system property
            // 清理系统属性
            System.clearProperty("mcp.input.test_input");
        }

        // The registration should succeed without throwing exception
        // 注册应该成功而不抛出异常
        assertDoesNotThrow(() -> {
            mcpClientManager.isClientOnline("test-server");
        });
    }

    @Test
    @DisplayName("Test JSON parsing error - JSON解析错误测试")
    void testJsonParsingError() {
        String invalidJson = "{ invalid json }";

        assertThrows(Exception.class, () -> {
            mcpClientManager.registerFromJson(invalidJson);
        }, "Should throw exception for invalid JSON");
    }

    @Test
    @DisplayName("Test duplicate registration - 重复注册测试")
    void testDuplicateRegistration() {
        // Register the same server twice
        // 重复注册同一个服务器
        try {
            java.lang.reflect.Field registryField = McpClientManager.class.getDeclaredField("descriptorRegistry");
            registryField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, McpClientDescriptor> registry =
                (java.util.Map<String, McpClientDescriptor>) registryField.get(mcpClientManager);

            registry.put("duplicate-server", mockDescriptor);
            when(mockDescriptor.getName()).thenReturn("duplicate-server");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // The second registration should be skipped (no exception thrown)
        // 第二次注册应该被跳过（不抛出异常）
        assertDoesNotThrow(() -> {
            mcpClientManager.registerFromJson(TEST_JSON_CONFIG);
        });
    }


    @Test
    @DisplayName("Test call tool - 测试工具调用")
    void testCallTool() {

        // The second registration should be skipped (no exception thrown)
        // 第二次注册应该被跳过（不抛出异常）
        assertDoesNotThrow(() -> {
            mcpClientManager.registerFromResource("mcp-servers.json");
        });


        Tool mcpTool = mcpClientManager.getMcpTool("everything", "add");
        System.out.println(mcpTool);

        ToolExecutor toolExecutor = new ToolExecutor(mcpTool
            , new ToolCall("add", "add", "{\"a\":1,\"b\":2}"));

        Object result = toolExecutor.execute();

        assertEquals("The sum of 1 and 2 is 3.", result);

        System.out.println(result);
    }
}
