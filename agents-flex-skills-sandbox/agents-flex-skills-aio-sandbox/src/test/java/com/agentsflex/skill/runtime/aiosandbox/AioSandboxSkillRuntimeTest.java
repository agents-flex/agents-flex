package com.agentsflex.skill.runtime.aiosandbox;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AioSandboxSkillRuntimeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private HttpServer server;
    private final Deque<String> responses = new ArrayDeque<>();
    private final List<RecordedCall> calls = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new QueueHandler());
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.stop(0);
    }

    @Test
    public void uploadsSkillAndPollsRunningCommand() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("demo");
        Files.write(new File(skillDirectory, "SKILL.md").toPath(), "body".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(skillDirectory, ".env").toPath(), "SECRET=value".getBytes(StandardCharsets.UTF_8));
        Skill skill = new Skill(skillDirectory.getAbsolutePath(),
            Collections.<String, Object>singletonMap("name", "demo"), "body");
        File secondSkillDirectory = temporaryFolder.newFolder("second");
        Files.write(new File(secondSkillDirectory, "SKILL.md").toPath(),
            "second body".getBytes(StandardCharsets.UTF_8));
        Skill secondSkill = new Skill(secondSkillDirectory.getAbsolutePath(),
            Collections.<String, Object>singletonMap("name", "second"), "second body");

        responses.add("{\"success\":true,\"data\":{\"session_id\":\"mkdir1\","
            + "\"status\":\"completed\",\"output\":\"\",\"exit_code\":0}}");
        responses.add("{\"success\":true,\"data\":{}}");
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"mkdir2\","
            + "\"status\":\"completed\",\"output\":\"\",\"exit_code\":0}}");
        responses.add("{\"success\":true,\"data\":{}}");
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"s1\","
            + "\"status\":\"running\",\"output\":\"first\"}}");
        responses.add("{\"success\":true,\"data\":{\"status\":\"completed\"}}");
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"s1\","
            + "\"status\":\"completed\",\"output\":\"first second\",\"exit_code\":0}}");

        AioSandboxSkillRuntime runtime = AioSandboxSkillRuntime.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .bearerToken("test-token")
            .build();

        List<Skill> preparedSkills = runtime.prepare(Arrays.asList(skill, secondSkill));
        String remoteBase = preparedSkills.get(0).getBasePath();
        String secondRemoteBase = preparedSkills.get(1).getBasePath();
        Map<String, String> environment = new HashMap<>();
        environment.put("DEMO", "true");
        SkillExecutionResult result = runtime.execute(
            new SkillExecutionRequest("echo test", remoteBase, 5000, environment));

        assertTrue(remoteBase.startsWith("/home/gem/workspace/skills/demo-"));
        assertTrue(secondRemoteBase.startsWith("/home/gem/workspace/skills/second-"));
        assertEquals("first second", result.getStdout());
        assertEquals("", result.getStderr());
        assertEquals(0, result.getExitCode());

        RecordedCall mkdir = calls.get(0);
        RecordedCall upload = calls.get(1);
        RecordedCall secondMkdir = calls.get(2);
        RecordedCall secondUpload = calls.get(3);
        RecordedCall execute = calls.get(4);
        RecordedCall wait = calls.get(5);
        RecordedCall output = calls.get(6);
        assertEquals("/v1/shell/exec", mkdir.path);
        assertEquals("/", JSON.parseObject(mkdir.body).getString("exec_dir"));
        assertEquals("/v1/file/write", upload.path);
        assertEquals("Bearer test-token", upload.authorization);
        JSONObject uploadBody = JSON.parseObject(upload.body);
        assertEquals("base64", uploadBody.getString("encoding"));
        assertEquals("body", new String(java.util.Base64.getDecoder().decode(
            uploadBody.getString("content")), StandardCharsets.UTF_8));
        assertEquals("/v1/shell/exec", secondMkdir.path);
        assertEquals("/v1/file/write", secondUpload.path);
        JSONObject secondUploadBody = JSON.parseObject(secondUpload.body);
        assertEquals("second body", new String(java.util.Base64.getDecoder().decode(
            secondUploadBody.getString("content")), StandardCharsets.UTF_8));
        assertEquals("/v1/shell/exec", execute.path);
        assertEquals("/v1/shell/wait", wait.path);
        assertEquals("/v1/shell/view", output.path);

        JSONObject executeBody = JSON.parseObject(execute.body);
        assertEquals(remoteBase, executeBody.getString("exec_dir"));
        assertEquals("(\nexport DEMO='true'\necho test\n)", executeBody.getString("command"));
        assertEquals(false, executeBody.getBooleanValue("async_mode"));
        assertEquals("s1", JSON.parseObject(wait.body).getString("id"));
        JSONObject outputBody = JSON.parseObject(output.body);
        assertEquals("s1", outputBody.getString("id"));
        runtime.close();
    }

    @Test
    public void readsAndWritesThroughAioFileApi() {
        responses.add("{\"success\":true,\"data\":{\"content\":\"hello runtime\"}}");
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"mkdir\","
            + "\"status\":\"completed\",\"output\":\"\",\"exit_code\":0}}");
        responses.add("{\"success\":true,\"data\":{}}");

        AioSandboxSkillRuntime runtime = AioSandboxSkillRuntime.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .build();
        SkillRuntimeFileSystem files = runtime.getFileSystem();

        assertEquals("hello runtime", files.readText("/workspace/input.txt", 1024));
        files.writeText("/workspace/output.txt", "updated");

        assertEquals("/v1/file/read", calls.get(0).path);
        assertEquals("/workspace/input.txt", JSON.parseObject(calls.get(0).body).getString("file"));
        assertEquals("/v1/shell/exec", calls.get(1).path);
        assertEquals("/v1/file/write", calls.get(2).path);
        JSONObject write = JSON.parseObject(calls.get(2).body);
        assertEquals("/workspace/output.txt", write.getString("file"));
        assertEquals("updated", write.getString("content"));
        assertEquals("utf-8", write.getString("encoding"));
    }

    @Test
    public void downloadsBinaryFileThroughAioFileApi() {
        responses.add("binary-content");

        AioSandboxSkillRuntime runtime = AioSandboxSkillRuntime.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .bearerToken("download-token")
            .build();

        byte[] bytes = runtime.getFileSystem().readBytes("/workspace/report.pdf", 1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        responses.add("streamed-content");
        runtime.getFileSystem().download("/workspace/report.pdf", output);

        assertArrayEquals("binary-content".getBytes(StandardCharsets.UTF_8), bytes);
        assertEquals("streamed-content", new String(output.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("/v1/file/download", calls.get(0).path);
        assertEquals("Bearer download-token", calls.get(0).authorization);
        assertEquals("/v1/file/download", calls.get(1).path);
    }

    @Test
    public void returnsNullForMissingFileWithoutTerminatingShellSession() {
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"stat\","
            + "\"status\":\"completed\",\"output\":\"\",\"exit_code\":44}}");

        AioSandboxSkillRuntime runtime = AioSandboxSkillRuntime.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .build();

        assertNull(runtime.getFileSystem().stat("/workspace/missing.txt"));
        JSONObject body = JSON.parseObject(calls.get(0).body);
        assertTrue(body.getString("command").startsWith("(\nif [ -f "));
        assertTrue(body.getString("command").endsWith("fi\n)"));
    }

    @Test
    public void listsDirectFilesAndDirectoriesThroughShell() {
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"stat\","
            + "\"status\":\"completed\",\"output\":\"directory\\n0\\n\",\"exit_code\":0}}");
        responses.add("{\"success\":true,\"data\":{\"session_id\":\"list\","
            + "\"status\":\"completed\",\"output\":\"d\\t/workspace/src\\nf\\t/workspace/src/Main.java\\n"
            + "f\\t/workspace/README.md\\n\","
            + "\"exit_code\":0}}");

        AioSandboxSkillRuntime runtime = AioSandboxSkillRuntime.builder()
            .baseUrl("http://localhost:" + server.getAddress().getPort())
            .build();

        List<com.agentsflex.skill.runtime.SkillFileInfo> entries =
            runtime.getFileSystem().listDirectory("/workspace", 2, 100);

        assertEquals(3, entries.size());
        assertEquals("/workspace/src", entries.get(0).getPath());
        assertTrue(entries.get(0).isDirectory());
        assertEquals("/workspace/src/Main.java", entries.get(1).getPath());
        assertEquals(false, entries.get(1).isDirectory());
        assertEquals("/workspace/README.md", entries.get(2).getPath());
        assertTrue(JSON.parseObject(calls.get(1).body).getString("command")
            .contains("-mindepth 1 -maxdepth 2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRootAsRemoteDirectory() {
        AioSandboxSkillRuntime.builder().remoteRoot("/").build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidBaseUrl() {
        AioSandboxSkillRuntime.builder().baseUrl("localhost:8080").build();
    }

    private class QueueHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(readAll(exchange), StandardCharsets.UTF_8);
            calls.add(new RecordedCall(exchange.getRequestURI().getPath(), body,
                exchange.getRequestHeaders().getFirst("Authorization")));
            String response = responses.removeFirst();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static class RecordedCall {

        private final String path;
        private final String body;
        private final String authorization;

        private RecordedCall(String path, String body, String authorization) {
            this.path = path;
            this.body = body;
            this.authorization = authorization;
        }
    }
}
