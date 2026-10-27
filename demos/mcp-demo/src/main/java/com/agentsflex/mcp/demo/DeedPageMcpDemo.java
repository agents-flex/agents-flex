package com.agentsflex.mcp.demo;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.mcp.client.McpClientManager;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Connects to a remote Streamable HTTP MCP server (deed.page) and calls its tools
 * directly, without a chat model. deed.page needs no account or API key.
 * <p>
 * Run from the repository root:
 * <pre>
 * mvn -q -pl demos/mcp-demo -am install -DskipTests
 * mvn -q -f demos/mcp-demo/pom.xml exec:java -Dexec.mainClass=com.agentsflex.mcp.demo.DeedPageMcpDemo \
 *     [-Dexec.args="page.html --keep"]
 * </pre>
 * By default it publishes a sample page that expires after 10 minutes, fetches it,
 * then deletes it. With {@code --keep} the page stays online and the owner token is
 * printed once: keep it to update or delete the page later.
 * Published pages are public.
 */
public class DeedPageMcpDemo {

    private static final String SERVER = "deed-page";

    private static final String CONFIG = "{\n"
        + "  \"mcpServers\": {\n"
        + "    \"deed-page\": {\n"
        + "      \"transport\": \"http-stream\",\n"
        + "      \"url\": \"https://deed.page/mcp\"\n"
        + "    }\n"
        + "  }\n"
        + "}";

    private static final String SAMPLE_PAGE = "<!doctype html><meta charset=\"utf-8\">"
        + "<title>Agents-Flex MCP demo</title>"
        + "<h1>Agents-Flex MCP demo</h1><p>Published through a Streamable HTTP MCP server.</p>";

    public static void main(String[] args) throws Exception {
        boolean keep = false;
        String html = SAMPLE_PAGE;
        for (String arg : args) {
            if ("--keep".equals(arg)) {
                keep = true;
            } else {
                html = new String(Files.readAllBytes(Path.of(arg)), StandardCharsets.UTF_8);
            }
        }

        McpClientManager manager = McpClientManager.getInstance();
        manager.registerFromJson(CONFIG);
        try {
            Tool publish = requireTool(manager, "publish_site");
            Tool delete = requireTool(manager, "delete_site");

            Map<String, Object> files = new HashMap<>();
            files.put("index.html", html);
            Map<String, Object> publishArgs = new HashMap<>();
            publishArgs.put("files", files);
            if (!keep) {
                publishArgs.put("ttl_seconds", 600);
            }
            JSONObject site = JSON.parseObject(String.valueOf(publish.invoke(publishArgs)));
            String url = site.getString("url");

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<Void> page = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            System.out.println("Published " + url + " (HTTP " + page.statusCode() + ")");

            if (keep) {
                System.out.println("Kept the page. Save this owner token to update or delete it:");
                System.out.println(site.getString("token"));
                return;
            }

            Map<String, Object> deleteArgs = new HashMap<>();
            deleteArgs.put("slug", site.getString("slug"));
            deleteArgs.put("token", site.getString("token"));
            System.out.println("Deleted: " + delete.invoke(deleteArgs));
        } finally {
            manager.close();
        }
    }

    private static Tool requireTool(McpClientManager manager, String name) {
        Tool tool = manager.getMcpTool(SERVER, name);
        if (tool == null) {
            throw new IllegalStateException("MCP tool not found: " + name);
        }
        return tool;
    }
}
