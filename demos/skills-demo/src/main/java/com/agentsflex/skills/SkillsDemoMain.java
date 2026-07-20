package com.agentsflex.skills;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;
import com.agentsflex.model.chat.openai.OpenAIChatModel;
import com.agentsflex.skill.Skill;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.local.LocalSkillRuntime;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.aiosandbox.AioSandboxSkillRuntime;
import com.agentsflex.skill.runtime.opensandbox.OpenSandboxSkillRuntime;
import com.agentsflex.skill.util.Skills;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skills Runtime 端到端示例，支持 Local、OpenSandbox 和 AIO Sandbox。
 *
 * <p>示例首先从 classpath 或 {@code SKILLS_DIR} 发现全部 Skills，再根据
 * {@code SKILLS_RUNTIME} 创建执行环境。默认任务会确定性地运行 {@code pptx} Skill
 * 自带生成器，然后把同一 Runtime 驱动的 Skill、Bash、文件和搜索工具交给模型完成
 * 二次验收。远程模式结束后，程序通过 {@code SkillRuntimeFileSystem.download} 把 PPTX
 * 回收到宿主机。</p>
 *
 * <p>该示例刻意不注册 Commons Shell/File 工具，避免模型在配置远程 Runtime 时绕过
 * Sandbox 操作宿主机。</p>
 */
public class SkillsDemoMain {

    private static final String DEFAULT_OUTPUT_FILE = "agentsflex-skills-runtime-report.pptx";
    private static final String DEFAULT_PROMPT_TEMPLATE =
        "请使用 pptx skill 完成一个端到端的 PowerPoint 生成与验收任务。\n\n"
            + "输出文件必须是：%s\n"
            + "请优先运行 pptx skill 提供的 scripts/create_runtime_report.py，不要修改 Skill 源目录。"
            + "生成脚本必须保留为输出目录中的 generate_presentation.py。\n\n"
            + "PPTX 必须严格为 5 页 16:9，并满足以下要求：\n"
            + "1. 第 1 页是封面，标题为 AgentsFlex Skills Runtime Report，副标题包含 Hello world。\n"
            + "2. 第 2 页说明 SkillRuntime 的 Discover、Prepare 和 Execute 职责。\n"
            + "3. 第 3 页比较 LocalSkillRuntime、OpenSandbox 和 AIO Sandbox。\n"
            + "4. 第 4 页展示 Discover -> Prepare -> Execute -> Verify -> Deliver 工作流。\n"
            + "5. 第 5 页包含产物验收清单和下载交付说明。\n"
            + "6. 元数据必须设置为 Title=AgentsFlex Skills Runtime Report、"
            + "Author=AgentsFlex Skills Demo、Subject=Skill runtime verification。\n\n"
            + "生成后必须实际运行验证，确认 PPTX 格式有效、恰好 5 页、元数据正确、"
            + "文件大小大于 10 KB，并能提取到 Hello world、LocalSkillRuntime、OpenSandbox、"
            + "AIO Sandbox 和 Deliver。若任何检查失败，请修复并重新验证。\n\n"
            + "最终回答必须列出：使用的 Skill、执行过的主要命令、PPTX 绝对路径、文件大小、"
            + "页数、元数据和文本验证结果。不要只描述方案，必须生成并验证文件。";

    /**
     * 运行 Demo。模型 API Key 和 Runtime 参数均从环境变量读取。
     *
     * @param args 非空时作为自定义用户问题；为空时执行默认 PPTX 验收任务
     * @throws Exception Runtime 创建、模型会话或产物下载失败
     */
    public static void main(String[] args) throws Exception {
        disableObservabilityByDefault();

        String apiKey = requireEnvironment("GITEE_APIKEY");
        String skillsDirectory = resolveSkillsDirectory();
        long conversationTimeoutSeconds = environmentLong("SKILLS_DEMO_TIMEOUT_SECONDS", 900L);

        try (SkillRuntime runtime = createRuntime()) {
            String outputFile = resolveOutputFile(runtime.getName());
            boolean defaultPrompt = isDefaultPrompt(args);
            boolean outputExpected = defaultPrompt || StringUtil.hasText(System.getenv("SKILLS_OUTPUT_FILE"));
            String userPrompt = resolvePrompt(args, outputFile);
            System.out.println("Skills directory: " + skillsDirectory);
            System.out.println("Skill runtime: " + runtime.getName());
            if (outputExpected) {
                System.out.println("Runtime output file: " + outputFile);
            }
            if (defaultPrompt) {
                generateDefaultPresentation(runtime, skillsDirectory, outputFile);
            }

            OpenAIChatModel chatModel = OpenAIChatConfig.builder()
                .provider(environment("GITEE_PROVIDER", "GiteeAI"))
                .endpoint(environment("GITEE_ENDPOINT", "https://ai.gitee.com"))
                .requestPath(environment("GITEE_REQUEST_PATH", "/v1/chat/completions"))
                .apiKey(apiKey)
                .model(environment("GITEE_MODEL", "Qwen3.5-35B-A3B"))
                .logEnabled(environmentBoolean("CHAT_LOG_ENABLED", false))
                .buildModel();

            MemoryPrompt prompt = new MemoryPrompt();
            prompt.setSystemMessage("Always use the available skills when they match the request. "
                + "Run every shell command with the provided Bash tool in the configured skill runtime.");
            prompt.addTools(SkillsTool.builder()
                .addSkillsDirectory(skillsDirectory)
                .runtime(runtime)
                .buildTools());
            prompt.addMessage(new UserMessage(userPrompt));

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            chatModel.chatStream(prompt, new ConversationListener(chatModel, prompt, completed, failure));

            if (!completed.await(conversationTimeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Conversation timed out after "
                    + conversationTimeoutSeconds + " seconds");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Conversation failed", failure.get());
            }
            if (outputExpected) {
                collectOutput(runtime, outputFile);
            }
        }
    }

    /**
     * 根据 {@code SKILLS_RUNTIME} 构建且只构建一个 Runtime。
     */
    private static SkillRuntime createRuntime() {
        String name = environment("SKILLS_RUNTIME", "local").trim().toLowerCase(Locale.ROOT);
        if ("local".equals(name)) {
            return new LocalSkillRuntime();
        }
        if ("open-sandbox".equals(name) || "opensandbox".equals(name)) {
            ConnectionConfig connection = ConnectionConfig.builder()
                .domain(requireEnvironment("OPEN_SANDBOX_DOMAIN"))
                .apiKey(requireEnvironment("OPEN_SANDBOX_API_KEY"))
                .build();
            return OpenSandboxSkillRuntime.builder()
                .connectionConfig(connection)
                .image(environment("OPEN_SANDBOX_IMAGE", "python:3.11"))
                .remoteRoot(environment("OPEN_SANDBOX_REMOTE_ROOT", "/workspace/skills"))
                .sandboxTimeout(Duration.ofSeconds(environmentLong("OPEN_SANDBOX_TIMEOUT_SECONDS", 600L)))
                .readyTimeout(Duration.ofSeconds(environmentLong("OPEN_SANDBOX_READY_TIMEOUT_SECONDS", 30L)))
                .build();
        }
        if ("aio-sandbox".equals(name) || "aio".equals(name) || "aiosandbox".equals(name)) {
            return AioSandboxSkillRuntime.builder()
                .baseUrl(environment("AIO_SANDBOX_BASE_URL", "http://localhost:8080"))
                .bearerToken(System.getenv("AIO_SANDBOX_TOKEN"))
                .remoteRoot(environment("AIO_SANDBOX_REMOTE_ROOT", "/home/gem/workspace/skills"))
                .httpTimeoutMillis(environmentTimeoutMillis("AIO_SANDBOX_HTTP_TIMEOUT_SECONDS", 660L))
                .build();
        }
        throw new IllegalArgumentException("Unsupported SKILLS_RUNTIME: " + name
            + ". Expected local, open-sandbox, or aio-sandbox.");
    }

    private static String resolveSkillsDirectory() throws Exception {
        String configured = System.getenv("SKILLS_DIR");
        if (StringUtil.hasText(configured)) {
            return requireDirectory(Paths.get(configured));
        }

        URL resource = SkillsDemoMain.class.getClassLoader().getResource(".claude/skills");
        if (resource != null && "file".equalsIgnoreCase(resource.getProtocol())) {
            return requireDirectory(Paths.get(resource.toURI()));
        }

        Path current = Paths.get("").toAbsolutePath();
        Path[] candidates = new Path[]{
            current.resolve("src/main/resources/.claude/skills"),
            current.resolve("demos/skills-demo/src/main/resources/.claude/skills")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.normalize().toString();
            }
        }
        throw new IllegalStateException("Skills directory not found. Set SKILLS_DIR to a filesystem directory.");
    }

    private static String requireDirectory(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("Skills directory does not exist: " + absolute);
        }
        return absolute.toString();
    }

    private static String resolvePrompt(String[] args, String outputFile) {
        if (args != null && args.length > 0) {
            return String.join(" ", args);
        }
        String configured = System.getenv("SKILLS_PROMPT");
        if (StringUtil.hasText(configured)) {
            return configured.trim().replace("${outputFile}", outputFile);
        }
        return String.format(DEFAULT_PROMPT_TEMPLATE, outputFile);
    }

    private static boolean isDefaultPrompt(String[] args) {
        return (args == null || args.length == 0) && !StringUtil.hasText(System.getenv("SKILLS_PROMPT"));
    }

    private static String resolveOutputFile(String runtimeName) throws Exception {
        String configured = System.getenv("SKILLS_OUTPUT_FILE");
        if (StringUtil.hasText(configured)) {
            return configured.trim();
        }
        if ("open-sandbox".equals(runtimeName)) {
            return environment("OPEN_SANDBOX_REMOTE_ROOT", "/workspace/skills")
                + "/output/" + DEFAULT_OUTPUT_FILE;
        }
        if ("aio-sandbox".equals(runtimeName)) {
            return environment("AIO_SANDBOX_REMOTE_ROOT", "/home/gem/workspace/skills")
                + "/output/" + DEFAULT_OUTPUT_FILE;
        }

        Path current = Paths.get("").toAbsolutePath().normalize();
        Path demoDirectory = Files.isDirectory(current.resolve("demos/skills-demo"))
            ? current.resolve("demos/skills-demo") : current;
        Path outputFile = demoDirectory.resolve("target/skills-demo-output")
            .resolve(DEFAULT_OUTPUT_FILE).normalize();
        Files.createDirectories(outputFile.getParent());
        return outputFile.toString();
    }

    /**
     * 验证本地产物，或将远程 Runtime 产物下载到宿主机。
     */
    private static void collectOutput(SkillRuntime runtime, String runtimeOutputFile) throws Exception {
        if ("local".equals(runtime.getName())) {
            Path localFile = Paths.get(runtimeOutputFile).toAbsolutePath().normalize();
            if (!Files.isRegularFile(localFile)) {
                throw new IllegalStateException("Expected output file was not created: " + localFile);
            }
            System.out.println("Local output file: " + localFile + " (" + Files.size(localFile) + " bytes)");
            return;
        }
        if (!environmentBoolean("SKILLS_DOWNLOAD_ENABLED", true)) {
            System.out.println("Remote output download is disabled: " + runtimeOutputFile);
            return;
        }
        Path localFile = resolveLocalOutputFile(runtimeOutputFile);
        runtime.getFileSystem().download(runtimeOutputFile, localFile);
        System.out.println("Downloaded output file: " + localFile + " (" + Files.size(localFile) + " bytes)");
    }

    /**
     * 在模型交互前运行 Skill 自带的确定性生成器，保证 Demo 必然产生可验收的 PPTX。
     */
    private static void generateDefaultPresentation(SkillRuntime runtime, String skillsDirectory,
                                                    String outputFile) {
        List<Skill> runtimeSkills = runtime.prepare(Skills.loadDirectory(skillsDirectory));
        Skill pptxSkill = null;
        for (Skill skill : runtimeSkills) {
            if ("pptx".equals(skill.name())) {
                pptxSkill = skill;
                break;
            }
        }
        if (pptxSkill == null) {
            throw new IllegalStateException("Default PPTX task requires the pptx skill");
        }

        long timeoutMillis = TimeUnit.SECONDS.toMillis(
            environmentLong("SKILLS_GENERATION_TIMEOUT_SECONDS", 300L));
        String generator = pptxSkill.getBasePath() + "/scripts/create_runtime_report.py";
        String command = "python3 -c " + shellQuote("import pptx")
            + " >/dev/null 2>&1 || python3 -m pip install python-pptx\n"
            + "python3 " + shellQuote(generator) + " " + shellQuote(outputFile);
        SkillExecutionResult result = runtime.execute(new SkillExecutionRequest(
            command, runtime.getDefaultWorkingDirectory(), timeoutMillis,
            Collections.<String, String>emptyMap()));
        if (result.isTimedOut() || result.getExitCode() != 0) {
            String detail = result.getStderr().isEmpty() ? result.getStdout() : result.getStderr();
            throw new IllegalStateException("Failed to generate runtime PPTX: " + detail);
        }
        System.out.println(result.getStdout());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Path resolveLocalOutputFile(String runtimeOutputFile) throws Exception {
        String configured = System.getenv("SKILLS_LOCAL_OUTPUT_FILE");
        if (StringUtil.hasText(configured)) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        Path fileName = Paths.get(runtimeOutputFile).getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Runtime output path has no file name: " + runtimeOutputFile);
        }
        Path current = Paths.get("").toAbsolutePath().normalize();
        Path demoDirectory = Files.isDirectory(current.resolve("demos/skills-demo"))
            ? current.resolve("demos/skills-demo") : current;
        return demoDirectory.resolve("target/skills-demo-output").resolve(fileName).normalize();
    }

    private static void disableObservabilityByDefault() {
        if (System.getProperty("agentsflex.otel.enabled") == null) {
            System.setProperty("agentsflex.otel.enabled", "false");
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (!StringUtil.hasText(value)) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value.trim();
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return StringUtil.hasText(value) ? value.trim() : defaultValue;
    }

    private static long environmentLong(String name, long defaultValue) {
        String value = System.getenv(name);
        if (!StringUtil.hasText(value)) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException("must be greater than zero");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer", e);
        }
    }

    private static boolean environmentBoolean(String name, boolean defaultValue) {
        String value = System.getenv(name);
        return StringUtil.hasText(value) ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static int environmentTimeoutMillis(String name, long defaultSeconds) {
        long millis = TimeUnit.SECONDS.toMillis(environmentLong(name, defaultSeconds));
        if (millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is too large");
        }
        return (int) millis;
    }

    private static class ConversationListener implements StreamResponseListener {

        private final OpenAIChatModel chatModel;
        private final MemoryPrompt prompt;
        private final CountDownLatch completed;
        private final AtomicReference<Throwable> failure;

        private ConversationListener(OpenAIChatModel chatModel, MemoryPrompt prompt, CountDownLatch completed,
                                     AtomicReference<Throwable> failure) {
            this.chatModel = chatModel;
            this.prompt = prompt;
            this.completed = completed;
            this.failure = failure;
        }

        @Override
        public void onMessage(StreamContext context, AiMessageResponse response) {
            try {
                if (response.isError()) {
                    System.err.println("Model error: " + response.getErrorMessage());
                    failure.compareAndSet(null, new IllegalStateException(response.getErrorMessage()));
                    completed.countDown();
                    return;
                }

                AiMessage message = response.getMessage();
                if (message == null) {
                    return;
                }
                String content = StringUtil.hasText(message.getContent())
                    ? message.getContent() : message.getReasoningContent();
                if (content != null) {
                    System.out.print(content);
                }

                if (!message.isFinalDelta()) {
                    return;
                }
                if (!message.hasToolCalls()) {
                    System.out.println("\n\nConversation completed.");
                    completed.countDown();
                    return;
                }

                prompt.addMessage(message);
                System.out.println("\n\n---------- tools ----------");
                for (ToolCall toolCall : message.getToolCalls()) {
                    System.out.println(toolCall.getName() + ": "
                        + JSON.toJSONString(toolCall.getArgsMap(), JSONWriter.Feature.PrettyFormat));
                }

                List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
                for (ToolMessage toolMessage : toolMessages) {
                    System.out.println("result: " + toolMessage.getContent());
                }
                System.out.println("---------------------------\n");
                prompt.addMessages(toolMessages);
                chatModel.chatStream(prompt, this);
            } catch (RuntimeException e) {
                e.printStackTrace(System.err);
                failure.compareAndSet(null, e);
                completed.countDown();
            }
        }

        @Override
        public void onFailure(StreamContext context, Throwable throwable) {
            if (throwable != null) {
                throwable.printStackTrace(System.err);
                failure.compareAndSet(null, throwable);
            }
            completed.countDown();
        }
    }
}
