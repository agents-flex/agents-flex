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
package com.agentsflex.subagent;

import com.agentsflex.core.model.chat.tool.Tool;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SubagentTools {

    private static final String EXECUTE_TASK_PROMPT_TEMPLATE = "Launch a new agent to handle complex, multi-step tasks autonomously.\n" +
        "\n" +
        "The `execute_task` tool launches specialized agents (subprocesses) that autonomously handle complex tasks. Each agent type has specific capabilities and tools available to it.\n" +
        "\n" +
        "Available task agents:\n" +
        "<available_task_agents>\n" +
        "%s\n" +
        "</available_task_agents>\n" +
        "\n" +
        "\n" +
        "IMPORTANT:\n" +
        "\n" +
        "When calling `execute_task` tool:\n" +
        "\n" +
        "1. Read the available agents from `<available_task_agents>`.\n" +
        "2. Use ONLY the value inside the `<name>` element.\n" +
        "3. The value must match exactly.\n" +
        "4. Never use text from `<description>`.\n" +
        "5. If no suitable agent exists, do not invent a new agent name." +
        "\n" +
        "\n" +
        "Usage notes:\n" +
        "- Always include a short description (3-5 words) summarizing what the agent will do\n" +
        "- Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses\n" +
        "- When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result.\n" +
        "- You can optionally run agents in the background using the run_in_background parameter. When an agent runs in the background, you will need to use `get_task_output` to retrieve its results once it's done. You can continue to work while background agents run - When you need their results to continue you can use `get_task_output` in blocking mode to pause and wait for their results.\n" +
        "- When running tasks in the background, the Task tool will return a task_id immediately. Use the `get_task_output` tool with this task_id to check status and retrieve results.\n" +
        "- Agents can be resumed using the `resume` parameter by passing the agent ID from a previous invocation. When resumed, the agent continues with its full previous context preserved. When NOT resuming, each invocation starts fresh and you should provide a detailed task description with all necessary context.\n" +
        "- When the agent is done, it will return a single message back to you along with its agent ID. You can use this ID to resume the agent later if needed for follow-up work.\n" +
        "- Provide clear, detailed prompts so the agent can work autonomously and return exactly the information you need.\n" +
        "- Agents with \"access to current context\" can see the full conversation history before the tool call. When using these agents, you can write concise prompts that reference earlier context (e.g., \"investigate the error discussed above\") instead of repeating information. The agent will receive all prior messages and understand the context.\n" +
        "- The agent's outputs should generally be trusted\n" +
        "- Clearly tell the agent whether you expect it to write code or just to do research (search, file reads, web fetches, etc.), since it is not aware of the user's intent\n" +
        "- If the agent description mentions that it should be used proactively, then you should try your best to use it without the user having to ask for it first. Use your judgement.\n" +
        "- If the user specifies that they want you to run agents \"in parallel\", you MUST send a single message with multiple Task tool use content blocks. For example, if you need to launch both a code-reviewer agent and a test-runner agent in parallel, send a single message with both tool calls.\n" +
        "\n\n" +
        "Example usage:\n" +
        "\n" +
        "<available_task_agents>\n" +
        "   <task_agent>\n" +
        "      <name>code-reviewer</name>\n" +
        "      <description>use this agent after you are done writing a signficant piece of code</description>\n" +
        "   </task_agent>\n" +
        "\n" +
        "   <task_agent>\n" +
        "      <name>greeting-responder</name>\n" +
        "      <description>use this agent when to respond to user greetings with a friendly joke</description>\n" +
        "   </task_agent>\n" +
        "</available_task_agents>" +
        "\n" +
        "<example>\n" +
        "user: \"Please write a function that checks if a number is prime\"\n" +
        "assistant: Sure let me write a function that checks if a number is prime\n" +
        "<code>\n" +
        "function isPrime(n) {\n" +
        "if (n <= 1) return false\n" +
        "for (let i = 2; i * i <= n; i++) {\n" +
        "\tif (n %% i === 0) return false\n" +
        "}\n" +
        "return true\n" +
        "}\n" +
        "</code>\n" +
        "<commentary>\n" +
        "Since a signficant piece of code was written and the task was completed, now use the code-reviewer agent to review the code\n" +
        "</commentary>\n" +
        "assistant: Now let me use the code-reviewer agent to review the code\n" +
        "assistant: Uses the `execute_task` tool to launch the code-reviewer agent\n" +
        "</example>\n" +
        "\n" +
        "<example>\n" +
        "user: \"Hello\"\n" +
        "<commentary>\n" +
        "Since the user is greeting, use the greeting-responder agent to respond with a friendly joke\n" +
        "</commentary>\n" +
        "assistant: \"I'm going to use the `execute_task` tool to launch the greeting-responder agent\"\n" +
        "</example>";


    private static final String GET_TASK_OUTPUT_PROMPT_TEMPLATE = "- Retrieves output from a running or completed task (background agent)\n" +
        "- Takes a task_id parameter identifying the task\n" +
        "- Returns the task output along with status information\n" +
        "- Use block=true (default) to wait for task completion\n" +
        "- Use block=false for non-blocking check of current status\n" +
        "- Task IDs can be found using the /tasks command";

    static class SubAgentFunction implements Function<SubagentArgs, String> {

        //name : "subagent"
        private final Map<String, SubagentDefinition> subagents;
        private final SubagentExecutor subagentExecutor;
        private final TaskRepository taskRepository;


        public SubAgentFunction(List<SubagentDefinition> definitions, TaskRepository taskRepository, SubagentExecutor subagentExecutor) {
            this.subagents = definitions.stream().collect(Collectors.toMap(SubagentDefinition::getName, Function.identity()));
            this.taskRepository = taskRepository;
            this.subagentExecutor = subagentExecutor;
        }

        @Override
        public String apply(SubagentArgs subagentArgs) {
            String subagentName = subagentArgs.getName();

            if (!this.subagents.containsKey(subagentName)) {
                return String.format("Error: Subagent '%s' not found, only support the names: %s", subagentName, this.subagents.keySet());
            }

            SubagentDefinition subagentDefinition = subagents.get(subagentName);

            if (Boolean.TRUE.equals(subagentArgs.getRun_in_background())) {
                BackgroundTask bgTask = taskRepository.putTask("task_" + UUID.randomUUID(),
                    () -> subagentExecutor.execute(subagentArgs, subagentDefinition));

                return String.format(
                    "task_id: %s\n\nBackground task started with ID: %s\nUse get_task_output tool with task_id='%s' to retrieve results.",
                    bgTask.getTaskId(), bgTask.getTaskId(), bgTask.getTaskId());
            }

            return subagentExecutor.execute(subagentArgs, subagentDefinition);
        }
    }

    static class GetTaskOutputFunction implements Function<OutputArgs, String> {

        private final TaskRepository taskRepository;

        GetTaskOutputFunction(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
        }

        @Override
        public String apply(OutputArgs outputArgs) {
            String taskId = outputArgs.getTask_id();
            BackgroundTask bgTask = taskRepository.getTasks(taskId);

            if (bgTask == null) {
                return "Error: No background task found with ID: " + taskId;
            }

            Boolean block = outputArgs.getBlock();
            boolean shouldBlock = block == null || block;
            Long timeout = outputArgs.getTimeout();
            long timeoutMs = timeout != null ? Math.min(timeout, 600000) : 30000;
            if (shouldBlock && !bgTask.isCompleted()) {
                try {
                    bgTask.waitForCompletion(timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Error: Wait for task interrupted";
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("Task ID: ").append(taskId).append("\n");
            result.append("Status: ").append(bgTask.getStatus()).append("\n\n");

            if (bgTask.isCompleted() && bgTask.getResult() != null) {
                result.append("Result:\n").append(bgTask.getResult());
            } else if (bgTask.getError() != null) {
                result.append("Error:\n").append(bgTask.getError().getMessage());
                if (bgTask.getError().getCause() != null) {
                    result.append("\nCause: ").append(bgTask.getError().getCause().getMessage());
                }
            } else if (!bgTask.isCompleted()) {
                result.append("Task still running...");
            }

            return result.toString();
        }
    }


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String executeTaskPromptTemplate = SubagentTools.EXECUTE_TASK_PROMPT_TEMPLATE;
        private String getTaskOutputPromptTemplate = SubagentTools.GET_TASK_OUTPUT_PROMPT_TEMPLATE;

        private final List<SubagentDefinition> definitions = new ArrayList<>();
        private TaskRepository taskRepository = new TaskRepository();
        private SubagentExecutor subagentExecutor;


        public Builder executeTaskPromptTemplate(String executeTaskPromptTemplate) {
            this.executeTaskPromptTemplate = executeTaskPromptTemplate;
            return this;
        }

        public Builder getTaskOutputPromptTemplate(String getTaskOutputPromptTemplate) {
            this.getTaskOutputPromptTemplate = getTaskOutputPromptTemplate;
            return this;
        }

        public Builder addDefinition(SubagentDefinition definition) {
            this.definitions.add(definition);
            return this;
        }

        public Builder addDefinitions(Collection<SubagentDefinition> definitions) {
            this.definitions.addAll(definitions);
            return this;
        }


        public Builder taskRepository(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
            return this;
        }

        public Builder subagentExecutor(SubagentExecutor subagentExecutor) {
            this.subagentExecutor = subagentExecutor;
            return this;
        }


        public List<Tool> build() {
            String subagentRegistrations = definitions.stream()
                .map(SubagentDefinition::toXml)
                .collect(Collectors.joining("\n\n"));

            Tool executeTaskTool = Tool.builder("execute_task", new SubAgentFunction(definitions, taskRepository, subagentExecutor))
                .inputType(SubagentArgs.class)
                .description(String.format(executeTaskPromptTemplate, subagentRegistrations))
                .build();

            Tool getTaskOutputTool = Tool.builder("get_task_output", new GetTaskOutputFunction(taskRepository))
                .inputType(OutputArgs.class)
                .description(getTaskOutputPromptTemplate)
                .build();

            List<Tool> tools = new ArrayList<>(2);
            tools.add(executeTaskTool);
            tools.add(getTaskOutputTool);
            return tools;
        }

    }


}
