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
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * @author Christian Tzolov
 * @author Michael Yang
 */
public class ShellTools {

    // Storage for background processes
    private static final Map<String, BackgroundProcess> backgroundProcesses = new ConcurrentHashMap<>();

    // Inner class to manage background processes
    private static class BackgroundProcess {

        final Process process;

        final StringBuilder stdout;

        final StringBuilder stderr;

        final Thread stdoutReader;

        final Thread stderrReader;

        int lastStdoutPosition = 0;

        int lastStderrPosition = 0;

        BackgroundProcess(Process process) {
            this.process = process;
            this.stdout = new StringBuilder();
            this.stderr = new StringBuilder();

            // Start thread to read stdout
            this.stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stdout) {
                            stdout.append(line).append("\n");
                        }
                    }
                } catch (IOException e) {
                    // Process terminated or stream closed
                }
            });
            this.stdoutReader.setDaemon(true);
            this.stdoutReader.start();

            // Start thread to read stderr
            this.stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderr) {
                            stderr.append(line).append("\n");
                        }
                    }
                } catch (IOException e) {
                    // Process terminated or stream closed
                }
            });
            this.stderrReader.setDaemon(true);
            this.stderrReader.start();
        }

        String getNewOutput(String filter) {
            StringBuilder result = new StringBuilder();

            synchronized (stdout) {
                String newStdout = stdout.substring(lastStdoutPosition);
                if (filter != null && !filter.isEmpty()) {
                    Pattern pattern = Pattern.compile(filter);
                    newStdout = filterOutput(newStdout, pattern);
                }
                if (!newStdout.isEmpty()) {
                    result.append("STDOUT:\n").append(newStdout);
                }
                lastStdoutPosition = stdout.length();
            }

            synchronized (stderr) {
                String newStderr = stderr.substring(lastStderrPosition);
                if (filter != null && !filter.isEmpty()) {
                    Pattern pattern = Pattern.compile(filter);
                    newStderr = filterOutput(newStderr, pattern);
                }
                if (!newStderr.isEmpty()) {
                    if (result.length() > 0)
                        result.append("\n");
                    result.append("STDERR:\n").append(newStderr);
                }
                lastStderrPosition = stderr.length();
            }

            return result.toString();
        }

        private String filterOutput(String output, Pattern pattern) {
            String[] lines = output.split("\n");
            StringBuilder filtered = new StringBuilder();
            for (String line : lines) {
                if (pattern.matcher(line).find()) {
                    filtered.append(line).append("\n");
                }
            }
            return filtered.toString();
        }

        boolean isAlive() {
            return process.isAlive();
        }

        void destroy() {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        int getExitCode() {
            return process.exitValue();
        }

    }

    //
    // Shell comnmands
    //

    // @formatter:off
	@ToolDef(name = "Bash", description = "Executes a given bash command in a persistent shell session with optional timeout, ensuring proper handling and security measures.\n" +
        "\n" +
        "IMPORTANT: This tool is for terminal operations like git, npm, docker, etc. DO NOT use it for file operations (reading, writing, editing, searching, finding files) - use the specialized tools for this instead.\n" +
        "\n" +
        "Before executing the command, please follow these steps:\n" +
        "\n" +
        "1. Directory Verification:\n" +
        "- If the command will create new directories or files, first use `ls` to verify the parent directory exists and is the correct location\n" +
        "- For example, before running \"mkdir foo/bar\", first use `ls foo` to check that \"foo\" exists and is the intended parent directory\n" +
        "\n" +
        "2. Command Execution:\n" +
        "- Always quote file paths that contain spaces with double quotes (e.g., cd \"path with spaces/file.txt\")\n" +
        "- Examples of proper quoting:\n" +
        "- cd \"/Users/[REDACTED]/My Documents\" (correct)\n" +
        "- cd /Users/[REDACTED]/My Documents (incorrect - will fail)\n" +
        "- python \"/path/with spaces/script.py\" (correct)\n" +
        "- python /path/with spaces/script.py (incorrect - will fail)\n" +
        "- After ensuring proper quoting, execute the command.\n" +
        "- Capture the output of the command.\n" +
        "\n" +
        "Usage notes:\n" +
        "- The command argument is required.\n" +
        "- You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). If not specified, commands will timeout after 120000ms (2 minutes).\n" +
        "- It is very helpful if you write a clear, concise description of what this command does in 5-10 words.\n" +
        "- If the output exceeds 30000 characters, output will be truncated before being returned to you.\n" +
        "- You can use the `run_in_background` parameter to run the command in the background, which allows you to continue working while the command runs. You can monitor the output using the Bash tool as it becomes available. Never use `run_in_background` to run 'sleep' as it will return immediately. You do not need to use '&' at the end of the command when using this parameter.\n" +
        "\n" +
        "- Avoid using Bash with the `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or when these commands are truly necessary for the task. Instead, always prefer using the dedicated tools for these commands:\n" +
        "- File search: Use Glob (NOT find or ls)\n" +
        "- Content search: Use Grep (NOT grep or rg)\n" +
        "- Read files: Use Read (NOT cat/head/tail)\n" +
        "- Edit files: Use Edit (NOT sed/awk)\n" +
        "- Write files: Use Write (NOT echo >/cat <<EOF)\n" +
        "- Communication: Output text directly (NOT echo/printf)\n" +
        "- When issuing multiple commands:\n" +
        "- If the commands are independent and can run in parallel, make multiple Bash tool calls in a single message. For example, if you need to run \"git status\" and \"git diff\", send a single message with two Bash tool calls in parallel.\n" +
        "- If the commands depend on each other and must run sequentially, use a single Bash call with '&&' to chain them together (e.g., `git add . && git commit -m \"message\" && git push`). For instance, if one operation must complete before another starts (like mkdir before cp, Write before Bash for git operations, or git add before git commit), run these operations sequentially instead.\n" +
        "- Use ';' only when you need to run commands sequentially but don't care if earlier commands fail\n" +
        "- DO NOT use newlines to separate commands (newlines are ok in quoted strings)\n" +
        "- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it.\n" +
        "<good-example>\n" +
        "pytest /foo/bar/tests\n" +
        "</good-example>\n" +
        "<bad-example>\n" +
        "cd /foo/bar && pytest tests\n" +
        "</bad-example>\n" +
        "\n" +
        "# Committing changes with git\n" +
        "\n" +
        "Only create commits when requested by the user. If unclear, ask first. When the user asks you to create a new git commit, follow these steps carefully:\n" +
        "\n" +
        "Git Safety Protocol:\n" +
        "- NEVER update the git config\n" +
        "- NEVER run destructive/irreversible git commands (like push --force, hard reset, etc) unless the user explicitly requests them\n" +
        "- NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it\n" +
        "- NEVER run force push to main/master, warn the user if they request it\n" +
        "- Avoid git commit --amend.  ONLY use --amend when either (1) user explicitly requested amend OR (2) adding edits from pre-commit hook (additional instructions below)\n" +
        "- Before amending: ALWAYS check authorship (git log -1 --format='%an %ae')\n" +
        "- NEVER commit changes unless the user explicitly asks you to. It is VERY IMPORTANT to only commit when explicitly asked, otherwise the user will feel that you are being too proactive.\n" +
        "\n" +
        "1. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following bash commands in parallel, each using the Bash tool:\n" +
        "- Run a git status command to see all untracked files.\n" +
        "- Run a git diff command to see both staged and unstaged changes that will be committed.\n" +
        "- Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.\n" +
        "2. Analyze all staged changes (both previously staged and newly added) and draft a commit message:\n" +
        "- Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.). Ensure the message accurately reflects the changes and their purpose (i.e. \"add\" means a wholly new feature, \"update\" means an enhancement to an existing feature, \"fix\" means a bug fix, etc.).\n" +
        "- Do not commit files that likely contain secrets (.env, credentials.json, etc). Warn the user if they specifically request to commit those files\n" +
        "- Draft a concise (1-2 sentences) commit message that focuses on the \"why\" rather than the \"what\"\n" +
        "- Ensure it accurately reflects the changes and their purpose\n" +
        "3. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following commands:\n" +
        "- Add relevant untracked files to the staging area.\n" +
        "- Create the commit with a message ending with:\n" +
        "\uD83E\uDD16 Generated with [Claude Code](https://claude.com/claude-code)\n" +
        "\n" +
        "Co-Authored-By: Claude <noreply@anthropic.com>\n" +
        "- Run git status after the commit completes to verify success.\n" +
        "Note: git status depends on the commit completing, so run it sequentially after the commit.\n" +
        "4. If the commit fails due to pre-commit hook changes, retry ONCE. If it succeeds but files were modified by the hook, verify it's safe to amend:\n" +
        "- Check authorship: git log -1 --format='%an %ae'\n" +
        "- Check not pushed: git status shows \"Your branch is ahead\"\n" +
        "- If both true: amend your commit. Otherwise: create NEW commit (never amend other developers' commits)\n" +
        "\n" +
        "Important notes:\n" +
        "- NEVER run additional commands to read or explore code, besides git bash commands\n" +
        "- NEVER use the TodoWrite or Task tools\n" +
        "- DO NOT push to the remote repository unless the user explicitly asks you to do so\n" +
        "- IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.\n" +
        "- If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit\n" +
        "- In order to ensure good formatting, ALWAYS pass the commit message via a HEREDOC, a la this example:\n" +
        "<example>\n" +
        "git commit -m \"$(cat <<'EOF'\n" +
        "Commit message here.\n" +
        "\n" +
        "\uD83E\uDD16 Generated with [Claude Code](https://claude.com/claude-code)\n" +
        "\n" +
        "Co-Authored-By: Claude <noreply@anthropic.com>\n" +
        "EOF\n" +
        ")\"\n" +
        "</example>\n" +
        "\n" +
        "# Creating pull requests\n" +
        "Use the gh command via the Bash tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.\n" +
        "\n" +
        "IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:\n" +
        "\n" +
        "1. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following bash commands in parallel using the Bash tool, in order to understand the current state of the branch since it diverged from the main branch:\n" +
        "- Run a git status command to see all untracked files\n" +
        "- Run a git diff command to see both staged and unstaged changes that will be committed\n" +
        "- Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote\n" +
        "- Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)\n" +
        "2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request summary\n" +
        "3. You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. run the following commands in parallel:\n" +
        "- Create new branch if needed\n" +
        "- Push to remote with -u flag if needed\n" +
        "- Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.\n" +
        "<example>\n" +
        "gh pr create --title \"the pr title\" --body \"$(cat <<'EOF'\n" +
        "\n" +
        "## Summary\n" +
        "<1-3 bullet points>\n" +
        "\n" +
        "## Test plan\n" +
        "[Bulleted markdown checklist of TODOs for testing the pull request...]\n" +
        "\n" +
        "\uD83E\uDD16 Generated with [Claude Code](https://claude.com/claude-code)\n" +
        "EOF\n" +
        ")\"\n" +
        "</example>\n" +
        "\n" +
        "Important:\n" +
        "- DO NOT use the TodoWrite or Task tools\n" +
        "- Return the PR URL when you're done, so the user can see it\n" +
        "\n" +
        "# Other common operations\n" +
        "- View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments")
	public String bash(
		@ToolParam(name = "command", description = "The command to execute") String command,
		@ToolParam(name = "timeout", description = "Optional timeout in milliseconds (max 600000)", required = false) Long timeout,
		@ToolParam(name = "description", description = "Clear, concise description of what this command does in 5-10 words, in active voice. Examples:\nInput: ls\nOutput: List files in current directory\n\nInput: git status\nOutput: Show working tree status\n\nInput: npm install\nOutput: Install package dependencies\n\nInput: mkdir foo\nOutput: Create directory 'foo'", required = false) String description,
		@ToolParam(name = "runInBackground", description = "Set to true to run this command in the background. Use BashOutput to read the output later.", required = false) Boolean runInBackground) { // @formatter:on

        // Generate unique shell ID for all executions
        String shellId = "shell_" + System.currentTimeMillis();

        try {
            // Determine the shell to use based on OS
            String[] shellCommand;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                shellCommand = new String[]{"cmd.exe", "/c", command};
            } else {
                shellCommand = new String[]{"/bin/bash", "-c", command};
            }

            ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
            processBuilder.redirectErrorStream(false);

            // Set working directory if available in tool context
            // processBuilder.directory(new File(workingDirectory));

            Process process = processBuilder.start();

            if (Boolean.TRUE.equals(runInBackground)) {
                // Run in background
                BackgroundProcess bgProcess = new BackgroundProcess(process);
                backgroundProcesses.put(shellId, bgProcess);

                return String.format(
                    "bash_id: %s\n\nBackground shell started with ID: %s\nUse BashOutput tool with bash_id='%s' to retrieve output.",
                    shellId, shellId, shellId);
            } else {
                // Run synchronously with timeout
                long timeoutMs = timeout != null ? Math.min(timeout, 600000) : 120000;

                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();

                // Read stdout
                Thread stdoutThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stdout.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                });

                // Read stderr
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stderr.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                });

                stdoutThread.start();
                stderrThread.start();

                boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

                if (!completed) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                    return String.format("bash_id: %s\n\nCommand timed out after %dms", shellId, timeoutMs);
                }

                stdoutThread.join(1000);
                stderrThread.join(1000);

                int exitCode = process.exitValue();
                StringBuilder result = new StringBuilder();

                // Add bash_id at the beginning
                result.append("bash_id: ").append(shellId).append("\n\n");

                if (stdout.length() > 0) {
                    result.append(stdout.toString());
                }

                if (stderr.length() > 0) {
                    if (result.length() > result.indexOf("\n\n") + 2)
                        result.append("\n");
                    result.append("STDERR:\n").append(stderr.toString());
                }

                if (exitCode != 0) {
                    if (result.length() > result.indexOf("\n\n") + 2)
                        result.append("\n");
                    result.append("Exit code: ").append(exitCode);
                }

                // Truncate if too long
                String output = result.toString();
                if (output.length() > 30000) {
                    // Keep the bash_id header
                    String header = output.substring(0, output.indexOf("\n\n") + 2);
                    String content = output.substring(output.indexOf("\n\n") + 2);
                    output = header + content.substring(0, Math.min(content.length(), 30000 - header.length()))
                        + "\n... (output truncated)";
                }

                return output;
            }

        } catch (IOException e) {
            return "Error executing command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Command execution interrupted: " + e.getMessage();
        }
    }

    // @formatter:off
	@ToolDef(name = "BashOutput", description = "- Retrieves output from a running or completed background bash shell\n" +
        "- Takes a shell_id parameter identifying the shell\n" +
        "- Always returns only new output since the last check\n" +
        "- Returns stdout and stderr output along with shell status\n" +
        "- Supports optional regex filtering to show only lines matching a pattern\n" +
        "- Use this tool when you need to monitor or check the output of a long-running shell\n" +
        "- Shell IDs can be found using the /bashes command")
	public String bashOutput(
		@ToolParam(name = "bash_id", description = "The ID of the background shell to retrieve output from") String bash_id,
		@ToolParam(name = "filter", description = "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result. Any lines that do not match will no longer be available to read.", required = false) String filter) { // @formatter:on

        BackgroundProcess bgProcess = backgroundProcesses.get(bash_id);

        if (bgProcess == null) {
            return "Error: No background shell found with ID: " + bash_id;
        }

        String newOutput = bgProcess.getNewOutput(filter);

        StringBuilder result = new StringBuilder();
        result.append("Shell ID: ").append(bash_id).append("\n");
        result.append("Status: ").append(bgProcess.isAlive() ? "Running" : "Completed").append("\n");

        if (!bgProcess.isAlive()) {
            try {
                result.append("Exit code: ").append(bgProcess.getExitCode()).append("\n");
            } catch (IllegalThreadStateException e) {
                // Process not yet terminated
            }
        }

        if (!newOutput.isEmpty()) {
            result.append("\nNew output:\n").append(newOutput);
        } else {
            result.append("\nNo new output since last check.");
        }

        return result.toString();
    }

    // @formatter:off
	@ToolDef(name = "KillShell", description = "- Kills a running background bash shell by its ID\n" +
        "- Takes a shell_id parameter identifying the shell to kill\n" +
        "- Returns a success or failure status\n" +
        "- Use this tool when you need to terminate a long-running shell\n" +
        "- Shell IDs can be found using the /bashes command")
	public String killShell(
		@ToolParam(name = "bash_id", description = "The ID of the background shell to kill") String bash_id) { // @formatter:on

        BackgroundProcess bgProcess = backgroundProcesses.get(bash_id);

        if (bgProcess == null) {
            return "Error: No background shell found with ID: " + bash_id;
        }

        if (!bgProcess.isAlive()) {
            backgroundProcesses.remove(bash_id);
            return "Shell " + bash_id + " was already terminated. Removed from active shells.";
        }

        bgProcess.destroy();

        // Wait a bit to confirm termination
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        backgroundProcesses.remove(bash_id);

        return "Successfully killed shell: " + bash_id;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        public ShellTools build() {
            return new ShellTools();
        }
    }

}
