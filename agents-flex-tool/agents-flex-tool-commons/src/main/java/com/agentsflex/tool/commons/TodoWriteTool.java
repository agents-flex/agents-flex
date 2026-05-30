/*
 * Copyright 2025 - 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentsflex.tool.commons;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Creates and manages structured task lists for AI coding sessions.
 * <p>
 * This is a Spring AI implementation of Claude Code's like TodoWrite tool, enabling AI
 * agents to track progress, organize complex tasks, and provide visibility into task
 * execution. The tool validates task states to ensure only one task is in progress at a
 * time and that all task data is properly formatted.
 *
 * @author Christian Tzolov
 * @author Michael Yang
 */
public class TodoWriteTool {

    private static final Logger logger = LoggerFactory.getLogger(TodoWriteTool.class);

    private final TodoEventHandler todoListConsumer;

    @FunctionalInterface
    public interface TodoEventHandler {
        void handle(Todos todos);
    }

    protected TodoWriteTool(TodoEventHandler todoListConsumer) {
        this.todoListConsumer = todoListConsumer;
    }

    // @formatter:off
	@ToolDef(name = "TodoWrite", description = "Use this tool to create and manage a structured task list for your current coding session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.\n" +
        "It also helps the user understand the progress of the task and overall progress of their requests.\n" +
        "\n" +
        "## When to Use This Tool\n" +
        "Use this tool proactively in these scenarios:\n" +
        "\n" +
        "1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions\n" +
        "2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations\n" +
        "3. User explicitly requests todo list - When the user directly asks you to use the todo list\n" +
        "4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)\n" +
        "5. After receiving new instructions - Immediately capture user requirements as todos\n" +
        "6. When you start working on a task - Mark it as in_progress BEFORE beginning work. Ideally you should only have one todo as in_progress at a time\n" +
        "7. After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation\n" +
        "\n" +
        "## When NOT to Use This Tool\n" +
        "\n" +
        "Skip using this tool when:\n" +
        "1. There is only a single, straightforward task\n" +
        "2. The task is trivial and tracking it provides no organizational benefit\n" +
        "3. The task can be completed in less than 3 trivial steps\n" +
        "4. The task is purely conversational or informational\n" +
        "\n" +
        "NOTE that you should not use this tool if there is only one trivial task to do. In this case you are better off just doing the task directly.\n" +
        "\n" +
        "## Validation Rules\n" +
        "\n" +
        "The tool validates the following:\n" +
        "- Only ONE task can be in_progress at a time\n" +
        "- Task content and activeForm must not be empty or blank\n" +
        "- All tasks must have valid status values (pending, in_progress, completed)\n" +
        "\n" +
        "## Examples of When to Use the Todo List\n" +
        "\n" +
        "<example>\n" +
        "User: I want to add a dark mode toggle to the application settings. Make sure you run the tests and build when you're done!\n" +
        "Assistant: I'll help add a dark mode toggle to your application settings. Let me create a todo list to track this implementation.\n" +
        "*Creates todo list with the following items:*\n" +
        "1. Creating dark mode toggle component in Settings page\n" +
        "2. Adding dark mode state management (context/store)\n" +
        "3. Implementing CSS-in-JS styles for dark theme\n" +
        "4. Updating existing components to support theme switching\n" +
        "5. Running tests and build process, addressing any failures or errors that occur\n" +
        "*Begins working on the first task*\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant used the todo list because:\n" +
        "1. Adding dark mode is a multi-step feature requiring UI, state management, and styling changes\n" +
        "2. The user explicitly requested tests and build be run afterward\n" +
        "3. The assistant inferred that tests and build need to pass by adding \"Ensure tests and build succeed\" as the final task\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "<example>\n" +
        "User: Help me rename the function getCwd to getCurrentWorkingDirectory across my project\n" +
        "Assistant: Let me first search through your codebase to find all occurrences of 'getCwd'.\n" +
        "*Uses grep or search tools to locate all instances of getCwd in the codebase*\n" +
        "Assistant: I've found 15 instances of 'getCwd' across 8 different files. Let me create a todo list to track these changes.\n" +
        "*Creates todo list with specific items for each file that needs updating*\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant used the todo list because:\n" +
        "1. First, the assistant searched to understand the scope of the task\n" +
        "2. Upon finding multiple occurrences across different files, it determined this was a complex task with multiple steps\n" +
        "3. The todo list helps ensure every instance is tracked and updated systematically\n" +
        "4. This approach prevents missing any occurrences and maintains code consistency\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "\n" +
        "<example>\n" +
        "User: I need to implement these features for my e-commerce site: user registration, product catalog, shopping cart, and checkout flow.\n" +
        "Assistant: I'll help implement these features. First, let's add all the features to the todo list.\n" +
        "*Creates a todo list breaking down each feature into specific tasks based on the project architecture*\n" +
        "Assistant: Let's start with user registration. This will involve creating database models, API endpoints, and frontend forms that integrate with your existing authentication system.\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant used the todo list because:\n" +
        "1. The user provided multiple complex features to implement in a comma separated list\n" +
        "2. The todo list helps organize these large features into manageable tasks\n" +
        "3. This approach allows for tracking progress across the entire implementation\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "<example>\n" +
        "User: Can you help optimize my React application? It's rendering slowly and has performance issues.</user>\n" +
        "Assistant: I'll help optimize your React application. First, let me examine your codebase to identify potential performance bottlenecks.\n" +
        "*Reviews component structure, render patterns, state management, and data fetching*\n" +
        "Assistant: After analyzing your codebase, I've identified several performance issues. Let me create a todo list to track our optimization efforts.\n" +
        "*Creates todo list with items like: 1) Implementing memoization for expensive calculations in ProductList, 2) Adding virtualization for long lists in Dashboard, 3) Optimizing image loading in Gallery component, 4) Fixing state update loops in ShoppingCart, 5) Reviewing bundle size and implementing code splitting*\n" +
        "Let's start by implementing memoization for the expensive calculations in your ProductList component.</assistant>\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant used the todo list because:\n" +
        "1. First, the assistant examined the codebase to identify specific performance issues\n" +
        "2. Based on this analysis, it identified multiple optimization opportunities\n" +
        "3. Performance optimization is a non-trivial task requiring multiple steps\n" +
        "4. The todo list helps methodically track improvements across different components\n" +
        "5. This systematic approach ensures all performance bottlenecks are addressed\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "## Examples of When NOT to Use the Todo List\n" +
        "\n" +
        "<example>\n" +
        "User: How do I print 'Hello World' in Python?\n" +
        "Assistant: In Python, you can print \"Hello World\" with this simple code:\n" +
        "\n" +
        "python\n" +
        "print(\"Hello World\")\n" +
        "\n" +
        "This will output the text \"Hello World\" to the console when executed.</assistant>\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant did not use the todo list because this is a single, trivial task that can be completed in one step. There's no need to track multiple tasks or steps for such a straightforward request.\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "<example>\n" +
        "User: What does the git status command do?\n" +
        "Assistant: The git status command shows the current state of your working directory and staging area. It displays which changes have been staged, which haven't, and which files aren't being tracked by Git. This is a fundamental command for understanding the current state of your repository before making commits.\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant did not use the todo list because this is an informational request with no actual coding task to complete. The user is simply asking for an explanation, not for the assistant to perform multiple steps or tasks.\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "<example>\n" +
        "User: Can you add a comment to the calculateTotal function to explain what it does?\n" +
        "Assistant: Sure, let me add a comment to the calculateTotal function to explain what it does.\n" +
        "* Uses the Edit tool to add a comment to the calculateTotal function *\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant did not use the todo list because this is a single, straightforward task confined to one location in the code. Adding a comment doesn't require tracking multiple steps or systematic organization.\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "<example>\n" +
        "User: Run npm install for me and tell me what happens.\n" +
        "Assistant: I'll run the npm install command for you.\n" +
        "\n" +
        "*Executes: npm install*\n" +
        "\n" +
        "The command completed successfully. Here's the output:\n" +
        "[Output of npm install command]\n" +
        "\n" +
        "All dependencies have been installed according to your package.json file.\n" +
        "\n" +
        "<reasoning>\n" +
        "The assistant did not use the todo list because this is a single command execution with immediate results. There are no multiple steps to track or organize, making the todo list unnecessary for this straightforward task.\n" +
        "</reasoning>\n" +
        "</example>\n" +
        "\n" +
        "## Task States and Management\n" +
        "\n" +
        "1. **Task States**: Use these states to track progress:\n" +
        "- pending: Task not yet started\n" +
        "- in_progress: Currently working on (limit to ONE task at a time)\n" +
        "- completed: Task finished successfully\n" +
        "\n" +
        "**IMPORTANT**: Task descriptions must have two forms:\n" +
        "- content: The imperative form describing what needs to be done (e.g., \"Run tests\", \"Build the project\")\n" +
        "- activeForm: The present continuous form shown during execution (e.g., \"Running tests\", \"Building the project\")\n" +
        "\n" +
        "2. **Task Management**:\n" +
        "- Update task status in real-time as you work\n" +
        "- Mark tasks complete IMMEDIATELY after finishing (don't batch completions)\n" +
        "- Exactly ONE task must be in_progress at any time (not less, not more)\n" +
        "- Complete current tasks before starting new ones\n" +
        "- Remove tasks that are no longer relevant from the list entirely\n" +
        "\n" +
        "3. **Task Completion Requirements**:\n" +
        "- ONLY mark a task as completed when you have FULLY accomplished it\n" +
        "- If you encounter errors, blockers, or cannot finish, keep the task as in_progress\n" +
        "- When blocked, create a new task describing what needs to be resolved\n" +
        "- Never mark a task as completed if:\n" +
        "\t- Tests are failing\n" +
        "\t- Implementation is partial\n" +
        "\t- You encountered unresolved errors\n" +
        "\t- You couldn't find necessary files or dependencies\n" +
        "\n" +
        "4. **Task Breakdown**:\n" +
        "- Create specific, actionable items\n" +
        "- Break complex tasks into smaller, manageable steps\n" +
        "- Use clear, descriptive task names\n" +
        "- Always provide both forms:\n" +
        "\t- content: \"Fix authentication bug\"\n" +
        "\t- activeForm: \"Fixing authentication bug\"\n" +
        "\n" +
        "When in doubt, use this tool. Being proactive with task management demonstrates attentiveness and ensures you complete all requirements successfully.")
	public String todoWrite(Todos todos) { // @formatter:on

        // Validate the todos
        this.validateTodos(todos);

        this.todoListConsumer.handle(todos);

        return "Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress. Please proceed with the current tasks if applicable";
    }

    /**
     * Validates the todo list according to the following rules: - Only one task can be
     * in_progress at a time - Task content and activeForm must not be empty or blank -
     * All tasks must have valid status values
     *
     * @param todos the todo list to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateTodos(Todos todos) {
        if (todos == null || todos.todos == null) {
            throw new IllegalArgumentException("Todos cannot be null");
        }

        List<Todos.TodoItem> items = todos.todos;

        // Validate each task first (before counting in_progress tasks)
        for (int i = 0; i < items.size(); i++) {
            Todos.TodoItem item = items.get(i);

            if (item == null) {
                throw new IllegalArgumentException("Task at index " + i + " is null");
            }

            if (StringUtil.noText(item.content)) {
                throw new IllegalArgumentException(
                    "Task at index " + i + " has empty or blank content. All tasks must have meaningful content.");
            }

            if (StringUtil.noText(item.activeForm)) {
                throw new IllegalArgumentException("Task at index " + i + " has empty or blank activeForm. "
                    + "All tasks must have an activeForm (present continuous tense).");
            }

            if (item.status == null) {
                throw new IllegalArgumentException("Task at index " + i
                    + " has null status. Status must be one of: pending, in_progress, completed");
            }
        }

        // Count in_progress tasks after validating all items
        long inProgressCount = items.stream().filter(item -> item.status == Todos.Status.in_progress).count();

        if (inProgressCount > 1) {
            throw new IllegalArgumentException("Only ONE task can be in_progress at a time. Found " + inProgressCount
                + " in_progress tasks. " + "Please mark the current task as completed before starting a new one.");
        }
    }

    public static class Todos {

        private List<TodoItem> todos;

        public Todos(List<TodoItem> todos) {
            this.todos = todos;
        }

        public List<TodoItem> getTodos() {
            return todos;
        }

        public void setTodos(List<TodoItem> todos) {
            this.todos = todos;
        }

        public static class TodoItem {

            private String content;
            private Status status;
            private String activeForm;

            public TodoItem(String content, Status status, String activeForm) {
                this.content = content;
                this.status = status;
                this.activeForm = activeForm;
            }

            public String getContent() {
                return content;
            }

            public void setContent(String content) {
                this.content = content;
            }

            public Status getStatus() {
                return status;
            }

            public void setStatus(Status status) {
                this.status = status;
            }

            public String getActiveForm() {
                return activeForm;
            }

            public void setActiveForm(String activeForm) {
                this.activeForm = activeForm;
            }
        }

        public enum Status {
            pending, in_progress, completed
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private TodoEventHandler todoEventHandler = todos -> logger.debug("Updated Todos: {}", todos);

        public Builder todoEventHandler(TodoEventHandler todoEventHandler) {
            this.todoEventHandler = todoEventHandler;
            return this;
        }

        public TodoWriteTool build() {
            return new TodoWriteTool(this.todoEventHandler);
        }
    }

}
