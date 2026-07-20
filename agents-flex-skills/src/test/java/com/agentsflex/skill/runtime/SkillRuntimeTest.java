package com.agentsflex.skill.runtime;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.Skill;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.file.FilePublishRequest;
import com.agentsflex.skill.file.FilePublisher;
import com.agentsflex.skill.file.PublishedFile;
import com.agentsflex.skill.local.LocalSkillRuntime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillRuntimeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void buildToolsRoutesSkillAndBashThroughSameRuntime() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("demo");
        Files.write(new File(skillDirectory, "SKILL.md").toPath(),
            "---\nname: demo\ndescription: demo skill\n---\nRun scripts/run.sh"
                .getBytes(StandardCharsets.UTF_8));
        File secondSkillDirectory = temporaryFolder.newFolder("second");
        Files.write(new File(secondSkillDirectory, "SKILL.md").toPath(),
            "---\nname: second\ndescription: second skill\n---\nSecond skill"
                .getBytes(StandardCharsets.UTF_8));
        RecordingRuntime runtime = new RecordingRuntime();

        List<Tool> tools = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .runtime(runtime)
            .buildTools();

        Tool skillTool = tool(tools, "Skill");
        Tool bashTool = tool(tools, "Bash");
        assertEquals(7, tools.size());
        tool(tools, "Read");
        tool(tools, "Write");
        tool(tools, "Edit");
        tool(tools, "Glob");
        tool(tools, "Grep");
        Object skillResult = skillTool.invoke(Collections.<String, Object>singletonMap("command", "demo"));

        Map<String, Object> bashArgs = new HashMap<>();
        bashArgs.put("command", "/runtime/demo/scripts/run.sh");
        bashArgs.put("timeout", 5000L);
        Object bashResult = bashTool.invoke(bashArgs);

        assertTrue(skillResult.toString().contains("Runtime: recording"));
        assertTrue(skillResult.toString().contains("Base directory for this skill: /runtime/demo"));
        assertEquals(1, runtime.prepareCalls);
        assertEquals(2, runtime.preparedSkillCount);
        assertEquals("/runtime/demo/scripts/run.sh", runtime.lastRequest.getCommand());
        assertEquals("remote output\n", bashResult);
    }

    @Test
    public void fileAndSearchToolsUseRuntimeFileSystem() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("runtime-files");
        Files.write(new File(skillDirectory, "SKILL.md").toPath(),
            "---\nname: files\ndescription: file tools\n---\nUse runtime files".getBytes(StandardCharsets.UTF_8));
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.fileSystem.writeText("/runtime/files/config.txt", "hello runtime\nsecond line\n");

        List<Tool> tools = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .runtime(runtime)
            .buildTools();

        Map<String, Object> readArgs = new HashMap<>();
        readArgs.put("filePath", "/runtime/files/config.txt");
        assertTrue(tool(tools, "Read").invoke(readArgs).toString().contains("hello runtime"));

        Map<String, Object> globArgs = new HashMap<>();
        globArgs.put("pattern", "*.txt");
        globArgs.put("path", "/runtime/files");
        assertEquals("/runtime/files/config.txt", tool(tools, "Glob").invoke(globArgs));

        runtime.fileSystem.writeText("/runtime/target/classes/skill.md", "target ancestor is allowed");
        globArgs.put("pattern", "*.md");
        globArgs.put("path", "/runtime/target/classes");
        assertEquals("/runtime/target/classes/skill.md", tool(tools, "Glob").invoke(globArgs));

        Map<String, Object> grepArgs = new HashMap<>();
        grepArgs.put("pattern", "runtime");
        grepArgs.put("path", "/runtime/files");
        grepArgs.put("outputMode", "content");
        assertTrue(tool(tools, "Grep").invoke(grepArgs).toString().contains("hello runtime"));

        Map<String, Object> editArgs = new HashMap<>();
        editArgs.put("filePath", "/runtime/files/config.txt");
        editArgs.put("old_string", "second line");
        editArgs.put("new_string", "updated line");
        tool(tools, "Edit").invoke(editArgs);
        assertTrue(runtime.fileSystem.readText("/runtime/files/config.txt", 1000).contains("updated line"));

        Map<String, Object> writeArgs = new HashMap<>();
        writeArgs.put("filePath", "/runtime/files/new.txt");
        writeArgs.put("content", "new file");
        tool(tools, "Write").invoke(writeArgs);
        assertEquals("new file", runtime.fileSystem.readText("/runtime/files/new.txt", 1000));
    }

    @Test
    public void configuredFilePublisherAddsToolAndPublishesRuntimeStream() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("attachment-skill");
        Files.write(new File(skillDirectory, "SKILL.md").toPath(),
            "---\nname: attachment\ndescription: publish attachment\n---\nPublish output"
                .getBytes(StandardCharsets.UTF_8));
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.fileSystem.writeText("/runtime/output/report.pptx", "presentation-bytes");
        final AtomicReference<FilePublishRequest> captured = new AtomicReference<>();
        final ByteArrayOutputStream publishedContent = new ByteArrayOutputStream();

        FilePublisher publisher = new FilePublisher() {
            @Override
            public PublishedFile publish(FilePublishRequest request) {
                captured.set(request);
                try {
                    byte[] buffer = new byte[64];
                    int read;
                    while ((read = request.getInputStream().read(buffer)) != -1) {
                        publishedContent.write(buffer, 0, read);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return PublishedFile.builder()
                    .url("https://files.example.test/attachments/runtime-report.pptx")
                    .fileName(request.getFileName())
                    .contentType(request.getContentType())
                    .contentLength(request.getContentLength())
                    .expiresAt(1893456000000L)
                    .build();
            }
        };

        List<Tool> tools = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .runtime(runtime)
            .filePublisher(publisher)
            .buildTools();

        assertEquals(8, tools.size());
        Map<String, Object> args = new HashMap<>();
        args.put("filePath", "/runtime/output/report.pptx");
        args.put("fileName", "runtime-report.pptx");
        args.put("contentType", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        Object result = tool(tools, "PublishFile").invoke(args);

        assertEquals("presentation-bytes", new String(publishedContent.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("recording", captured.get().getRuntimeName());
        assertEquals("/runtime/output/report.pptx", captured.get().getSourcePath());
        assertEquals("runtime-report.pptx", captured.get().getFileName());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
            captured.get().getContentType());
        assertEquals(18, captured.get().getContentLength());
        assertTrue(result.toString().contains("https://files.example.test/attachments/runtime-report.pptx"));
        assertTrue(result.toString().contains("Expires at: 1893456000000"));
    }

    @Test
    public void localRuntimeExecutesInConfiguredWorkingDirectory() throws Exception {
        File directory = temporaryFolder.newFolder("working-directory");
        SkillExecutionResult result = new LocalSkillRuntime().execute(
            new SkillExecutionRequest("pwd", directory.getAbsolutePath(), 5000, Collections.emptyMap()));

        assertEquals(0, result.getExitCode());
        assertEquals(directory.getCanonicalPath(), result.getStdout().trim());
    }

    @Test
    public void runtimeFileCanBeReadAsBytesAndDownloadedToLocalPath() throws Exception {
        byte[] expected = new byte[]{0, 1, 2, 3, 127, (byte) 255};
        File source = new File(temporaryFolder.newFolder("runtime-output"), "report.bin");
        Files.write(source.toPath(), expected);
        SkillRuntimeFileSystem files = new LocalSkillRuntime().getFileSystem();

        assertArrayEquals(expected, files.readBytes(source.getAbsolutePath(), 1024));

        File destination = new File(temporaryFolder.newFolder("downloads"), "report.bin");
        assertEquals(destination.getCanonicalFile().toPath(), files.download(
            source.getAbsolutePath(), destination.toPath()).toFile().getCanonicalFile().toPath());
        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));

        ByteArrayOutputStream thirdPartyDestination = new ByteArrayOutputStream();
        files.download(source.getAbsolutePath(), thirdPartyDestination);
        assertArrayEquals(expected, thirdPartyDestination.toByteArray());
    }

    @Test
    public void remoteUploadPolicyExcludesCommonCredentialFiles() throws Exception {
        File root = temporaryFolder.newFolder("upload-policy");
        File gitDirectory = new File(root, ".git");
        assertTrue(gitDirectory.mkdir());

        assertFalse(SkillRuntimeFiles.shouldVisitDirectory(root.toPath(), gitDirectory.toPath()));
        assertFalse(SkillRuntimeFiles.shouldUploadFile(root.toPath(), new File(root, ".env").toPath()));
        assertFalse(SkillRuntimeFiles.shouldUploadFile(root.toPath(), new File(root, "credentials.json").toPath()));
        assertTrue(SkillRuntimeFiles.shouldUploadFile(root.toPath(), new File(root, "SKILL.md").toPath()));
    }

    private static Tool tool(List<Tool> tools, String name) {
        for (Tool tool : tools) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        throw new AssertionError("Tool not found: " + name);
    }

    private static class RecordingRuntime implements SkillRuntime {

        private SkillExecutionRequest lastRequest;
        private int prepareCalls;
        private int preparedSkillCount;
        private final MemoryFileSystem fileSystem = new MemoryFileSystem();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public List<Skill> prepare(List<Skill> skills) {
            prepareCalls++;
            preparedSkillCount = skills.size();
            List<Skill> prepared = new java.util.ArrayList<>();
            for (Skill skill : skills) {
                prepared.add(new Skill("/runtime/" + skill.name(), skill.getFrontMatter(), skill.getContent()));
            }
            return prepared;
        }

        @Override
        public String getDefaultWorkingDirectory() {
            return "/runtime";
        }

        @Override
        public SkillRuntimeFileSystem getFileSystem() {
            return fileSystem;
        }

        @Override
        public SkillExecutionResult execute(SkillExecutionRequest request) {
            this.lastRequest = request;
            return new SkillExecutionResult(0, "remote output\n", "", false);
        }

        @Override
        public void close() {
        }
    }

    private static class MemoryFileSystem implements SkillRuntimeFileSystem {

        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public InputStream openInputStream(String path) {
            String value = values.get(path);
            if (value == null) {
                throw new SkillRuntimeException("missing: " + path);
            }
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String readText(String path, int maxBytes) {
            String value = values.get(path);
            if (value == null) {
                throw new SkillRuntimeException("missing: " + path);
            }
            return value;
        }

        @Override
        public void writeText(String path, String content) {
            values.put(path, content);
        }

        @Override
        public SkillFileInfo stat(String path) {
            if (values.containsKey(path)) {
                return new SkillFileInfo(path, false, values.get(path).length(), 0);
            }
            String prefix = path.endsWith("/") ? path : path + "/";
            for (String file : values.keySet()) {
                if (file.startsWith(prefix)) {
                    return new SkillFileInfo(path, true, 0, 0);
                }
            }
            return null;
        }

        @Override
        public List<SkillFileInfo> listFiles(String path, int maxDepth, int maxResults) {
            List<SkillFileInfo> result = new ArrayList<>();
            String prefix = path.endsWith("/") ? path : path + "/";
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if ((entry.getKey().equals(path) || entry.getKey().startsWith(prefix)) && result.size() < maxResults) {
                    result.add(new SkillFileInfo(entry.getKey(), false, entry.getValue().length(), 0));
                }
            }
            return result;
        }
    }
}
