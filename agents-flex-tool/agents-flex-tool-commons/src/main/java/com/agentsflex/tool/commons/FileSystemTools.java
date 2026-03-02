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
import com.agentsflex.core.util.IOUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Christian Tzolov
 * @author Michael Yang
 */
public class FileSystemTools {

    @ToolDef(name = "Read", description = "Reads a file from the local filesystem. You can access any file directly by using this tool.\n" +
        "Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.\n" +
        "\n" +
        "Usage:\n" +
        "- The file_path parameter must be an absolute path, not a relative path\n" +
        "- By default, it reads up to 2000 lines starting from the beginning of the file\n" +
        "- You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file by not providing these parameters\n" +
        "- Any lines longer than 2000 characters will be truncated\n" +
        "- Results are returned using cat -n format, with line numbers starting at 1\n" +
        "- This tool allows Claude Code to read images (eg PNG, JPG, etc). When reading an image file the contents are presented visually as Claude Code is a multimodal LLM.\n" +
        "- This tool can read PDF files (.pdf). PDFs are processed page by page, extracting both text and visual content for analysis.\n" +
        "- This tool can read Jupyter notebooks (.ipynb files) and returns all cells with their outputs, combining code, text, and visualizations.\n" +
        "- This tool can only read files, not directories. To read a directory, use an ls command via the Bash tool.\n" +
        "- You can call multiple tools in a single response. It is always better to speculatively read multiple potentially useful files in parallel.\n" +
        "- You will regularly be asked to read screenshots. If the user provides a path to a screenshot, ALWAYS use this tool to view the file at the path. This tool will work with all temporary file paths.\n" +
        "- If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents.")
    public String read(
        @ToolParam(name = "filePath", description = "The absolute path to the file to read") String filePath,
        @ToolParam(name = "offset", description = "The line number to start reading from. Only provide if the file is too large to read at once", required = false) Integer offset,
        @ToolParam(name = "limit", description = "The number of lines to read. Only provide if the file is too large to read at once.", required = false) Integer limit) {

        try {
            File file = new File(filePath);

            if (!file.exists()) {
                return "Error: File does not exist: " + filePath;
            }

            if (file.isDirectory()) {
                return "Error: Path is a directory, not a file: " + filePath;
            }

            // Default values
            int startLine = offset != null ? offset : 1;
            int maxLines = limit != null ? limit : 2000;

            if (startLine < 1) {
                startLine = 1;
            }

            List<String> lines = new ArrayList<>();
            int currentLine = 0;
            int linesRead = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    currentLine++;

                    // Skip lines before the offset
                    if (currentLine < startLine) {
                        continue;
                    }

                    // Stop if we've read enough lines
                    if (linesRead >= maxLines) {
                        break;
                    }

                    // Truncate long lines to 2000 characters
                    if (line.length() > 2000) {
                        line = line.substring(0, 2000) + "... (line truncated)";
                    }

                    lines.add(String.format("%6d%s", currentLine, line));
                    linesRead++;
                }
            }

            if (lines.isEmpty()) {
                if (currentLine == 0) {
                    return "File is empty: " + filePath;
                } else {
                    return String.format("No lines to read. File has %d lines, but offset was %d", currentLine,
                        startLine);
                }
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("File: %s\n", filePath));
            result.append(
                String.format("Showing lines %d-%d of %d\n\n", startLine, startLine + linesRead - 1, currentLine));

            for (String line : lines) {
                result.append(line).append("\n");
            }

            return result.toString();

        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    // @formatter:off
	@ToolDef(name = "Write", description = "Writes a file to the local filesystem.\n" +
        "\n" +
        "Usage:\n" +
        "- This tool will overwrite the existing file if there is one at the provided path.\n" +
        "- If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.\n" +
        "- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.\n" +
        "- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.\n" +
        "- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.")
	public String write(
		@ToolParam(name = "filePath", description = "The absolute path to the file to write (must be absolute, not relative)") String filePath,
		@ToolParam(name = "content", description = "The content to write to the file") String content) { // @formatter:on

        try {
            content = content != null ? content : "";

            Path path = Paths.get(filePath);
            File file = path.toFile();

            // Create parent directories if they don't exist
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    return "Error: Failed to create parent directories for: " + filePath;
                }
            }

            // Check if file already exists
            boolean fileExists = file.exists();

            // Write content to file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                writer.write(content);
            }

            if (fileExists) {
                return String.format("Successfully overwrote file: %s (%d bytes)", filePath, content.length());
            } else {
                return String.format("Successfully created file: %s (%d bytes)", filePath, content.length());
            }

        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // @formatter:off
	@ToolDef(name = "Edit", description = "Performs exact string replacements in files.\n" +
        "\n" +
        "Usage:\n" +
        "- You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.\n" +
        "- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: spaces + line number + tab. Everything after that tab is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.\n" +
        "- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.\n" +
        "- Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.\n" +
        "- The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.\n" +
        "- Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.")
	public String edit(
		@ToolParam(name = "filePath", description = "The absolute path to the file to modify") String filePath,
		@ToolParam(name = "old_string", description = "The text to replace") String old_string,
		@ToolParam(name = "new_string", description = "The text to replace it with (must be different from old_string)") String new_string,
		@ToolParam(name = "replace_all", description = "Replace all occurences of old_string (default false)", required = false) Boolean replace_all) { // @formatter:on

        try {
            File file = new File(filePath);

            if (!file.exists()) {
                return "Error: File does not exist: " + filePath;
            }

            if (file.isDirectory()) {
                return "Error: Path is a directory, not a file: " + filePath;
            }

            // Validate that old_string and new_string are different
            if (old_string.equals(new_string)) {
                return "Error: old_string and new_string must be different";
            }

            // Read the entire file content preserving exact line endings
            String originalContent;
            try {
                originalContent = IOUtil.readUtf8(Files.newInputStream(file.toPath()));
            } catch (IOException e) {
                return "Error reading file content: " + e.getMessage();
            }

            // Count occurrences
            int occurrences = countOccurrences(originalContent, old_string);

            if (occurrences == 0) {
                return "Error: old_string not found in file: " + filePath;
            }

            boolean replaceAll = Boolean.TRUE.equals(replace_all);

            if (!replaceAll && occurrences > 1) {
                return String.format(
                    "Error: old_string appears %d times in the file. Either provide a larger string with more surrounding context to make it unique or use replace_all=true to change all instances.",
                    occurrences);
            }

            // Perform replacement
            String newContent;
            if (replaceAll) {
                // Replace all occurrences using literal string replacement
                newContent = replaceAll(originalContent, old_string, new_string);
            } else {
                // Replace first occurrence only
                newContent = replaceFirst(originalContent, old_string, new_string);
            }

            // Write the modified content back to the file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
                writer.write(newContent);
            }

            // Generate a snippet showing the context around the edit
            String snippet = generateEditSnippet(newContent, new_string);

            // Return formatted response matching Claude Code's Edit tool format
            return String.format(
                "The file %s has been updated. Here's the result of running `cat -n` on a snippet of the edited file:\n%s",
                filePath, snippet);

        } catch (IOException e) {
            return "Error editing file: " + e.getMessage();
        }
    }

    // Helper method to count occurrences of a substring
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    // Helper method to replace first occurrence
    private String replaceFirst(String text, String old_string, String new_string) {
        int index = text.indexOf(old_string);
        if (index == -1) {
            return text;
        }
        return text.substring(0, index) + new_string + text.substring(index + old_string.length());
    }

    // Helper method to replace all occurrences (literal, not regex)
    private String replaceAll(String text, String old_string, String new_string) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        int lastIndex = 0;

        while ((index = text.indexOf(old_string, lastIndex)) != -1) {
            result.append(text, lastIndex, index);
            result.append(new_string);
            lastIndex = index + old_string.length();
        }
        result.append(text.substring(lastIndex));

        return result.toString();
    }

    /**
     * Generates a formatted snippet of the file showing context around the edited
     * section. Matches Claude Code's Edit tool output format with line numbers and arrow
     * separator.
     *
     * @param fileContent the complete file content after editing
     * @param newString   the new string that was inserted (used to find the edit location)
     * @return formatted snippet with line numbers
     */
    private String generateEditSnippet(String fileContent, String newString) {
        String[] lines = fileContent.split("\n", -1);

        // Find the line where the new content appears
        int editStartLine = -1;
        int editEndLine = -1;

        // Split new_string into lines to find where it appears in the file
        String[] newLines = newString.split("\n", -1);

        // Search for the first line of the new content
        for (int i = 0; i < lines.length; i++) {
            if (newLines.length > 0 && lines[i].contains(newLines[0])) {
                // Check if subsequent lines match (for multi-line edits)
                boolean matches = true;
                for (int j = 1; j < newLines.length && i + j < lines.length; j++) {
                    if (!lines[i + j].contains(newLines[j])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    editStartLine = i;
                    editEndLine = i + newLines.length - 1;
                    break;
                }
            }
        }

        // If we didn't find the edit location, show the beginning of the file
        if (editStartLine == -1) {
            editStartLine = 0;
            editEndLine = Math.min(10, lines.length - 1);
        }

        // Show context: ~5 lines before and ~5 lines after the edit
        int contextBefore = 5;
        int contextAfter = 5;
        int startLine = Math.max(0, editStartLine - contextBefore);
        int endLine = Math.min(lines.length - 1, editEndLine + contextAfter);

        // Build the snippet with line numbers (1-indexed, right-aligned with arrow)
        StringBuilder snippet = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            // Line numbers are 1-indexed and right-aligned to 6 characters
            snippet.append(String.format("%6d→%s", i + 1, lines[i]));
            if (i < endLine) {
                snippet.append("\n");
            }
        }

        return snippet.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        public FileSystemTools build() {
            return new FileSystemTools();
        }

    }

}
