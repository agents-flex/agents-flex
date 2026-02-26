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
import com.agentsflex.core.util.StringUtil;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure Java glob implementation that doesn't require external tools. Uses Java NIO.2 for
 * file pattern matching and traversal.
 * <p>
 * Generated with Claude Code AI assistant.
 *
 * @author Christian Tzolov
 * @author Claude Code
 * @author Michael Yang
 */
public class GlobTool {

    private final int maxDepth;

    private final int maxResults;

    private final Path workingDirectory;

    /**
     * Constructor with configurable parameters.
     *
     * @param maxDepth         Maximum directory traversal depth to prevent infinite recursion
     *                         (default: 100)
     * @param maxResults       Maximum number of results to return (default: 1000)
     * @param workingDirectory The working directory to use when path is not specified.
     *                         If null, defaults to current JVM working directory.
     */
    protected GlobTool(int maxDepth, int maxResults, Path workingDirectory) {
        this.maxDepth = maxDepth;
        this.maxResults = maxResults;
        this.workingDirectory = workingDirectory;
    }

    // @formatter:off
	@ToolDef(name = "Glob", description = "- Fast file pattern matching tool that works with any codebase size\n" +
        "- Supports glob patterns like \"**/*.js\" or \"src/**/*.ts\"\n" +
        "- Returns matching file paths sorted by modification time\n" +
        "- Use this tool when you need to find files by name patterns\n" +
        "- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead\n" +
        "- You can call multiple tools in a single response. It is always better to speculatively perform multiple searches in parallel if they are potentially useful.")
	public String glob(
		@ToolParam(name = "pattern", description = "The glob pattern to match files against") String pattern,
		@ToolParam(name = "path", description = "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \\\"undefined\\\" or \\\"null\\\" - simply omit it for the default behavior. Must be a valid directory path if provided.", required = false) String path) { // @formatter:on

        if (StringUtil.noText(pattern)) {
            return "Error: The glob pattern must not be empty";
        } else {
            pattern = pattern.trim();
        }

        try {
            // Determine search path - use configured workingDirectory if path not specified
            Path searchPath;
            if (StringUtil.hasText(path)) {
                searchPath = Paths.get(path);
            } else if (this.workingDirectory != null) {
                searchPath = this.workingDirectory;
            } else {
                searchPath = Paths.get(".");
            }

            if (!Files.exists(searchPath)) {
                return "Error: Path does not exist: " + searchPath.toAbsolutePath();
            }

            if (!Files.isDirectory(searchPath)) {
                return "Error: Path is not a directory: " + searchPath.toAbsolutePath();
            }

            // Build glob matcher
            PathMatcher matcher = this.buildGlobMatcher(pattern);

            // Find matching files
            List<FileInfo> matchingFiles = new ArrayList<>();

            try (Stream<Path> paths = Files.walk(searchPath, this.maxDepth, FileVisitOption.FOLLOW_LINKS)) {
                paths.filter(Files::isRegularFile)
                    .filter(p -> !this.isIgnoredPath(p))
                    .filter(p -> this.matchesPattern(p, searchPath, matcher))
                    .limit(this.maxResults)
                    .forEach(file -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                            matchingFiles.add(new FileInfo(file, attrs.lastModifiedTime().toMillis()));
                        } catch (IOException e) {
                            // Skip files that can't be read
                            matchingFiles.add(new FileInfo(file, 0));
                        }
                    });
            }

            if (matchingFiles.isEmpty()) {
                return "No files found matching pattern: " + pattern;
            }

            // Sort by modification time (most recent first)
            matchingFiles.sort(Comparator.comparingLong(FileInfo::modificationTime).reversed());

            // Build result
            StringBuilder result = new StringBuilder();
            for (FileInfo fileInfo : matchingFiles) {
                result.append(fileInfo.path().toString()).append("\n");
            }

            return result.toString().trim();

        } catch (Exception e) {
            return "Error executing glob: " + e.getMessage();
        }
    }

    /**
     * Build a PathMatcher from the glob pattern
     */
    private PathMatcher buildGlobMatcher(String pattern) {
        // Handle both simple globs (*.java) and complex globs (**/*.java)
        String globPattern = pattern.startsWith("**/") ? pattern : "**/" + pattern;
        return FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
    }

    /**
     * Check if a path matches the glob pattern
     */
    private boolean matchesPattern(Path file, Path searchPath, PathMatcher matcher) {
        // Try matching against the full path
        if (matcher.matches(file)) {
            return true;
        }

        // Also try matching against the relative path from the search directory
        try {
            Path relativePath = searchPath.relativize(file);
            return matcher.matches(relativePath);
        } catch (IllegalArgumentException e) {
            // If we can't relativize, just use the matcher on the file itself
            return false;
        }
    }

    /**
     * Check if a file should be ignored (common ignore patterns)
     */
    private boolean isIgnoredPath(Path path) {
        String pathStr = path.toString();
        return pathStr.contains("/.git/") || pathStr.contains("/node_modules/") || pathStr.contains("/target/")
            || pathStr.contains("/build/") || pathStr.contains("/.idea/") || pathStr.contains("/.vscode/")
            || pathStr.contains("/dist/") || pathStr.contains("/__pycache__/");
    }

    /**
     * Record to hold file information
     */
    private static class FileInfo {
        private final Path path;
        private final long modificationTime;

        public FileInfo(Path path, long modificationTime) {
            this.path = path;
            this.modificationTime = modificationTime;
        }

        public Path path() {
            return path;
        }

        public long modificationTime() {
            return modificationTime;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private int maxDepth = 100;

        private int maxResults = 1000;

        private Path workingDirectory = null;

        private Builder() {
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /**
         * Set the working directory to use when the agent doesn't specify a path.
         * This allows tools to operate within a sandbox/workspace context.
         *
         * @param workingDirectory the working directory path
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Set the working directory using a string path.
         *
         * @param workingDirectory the working directory path as string
         * @return this builder
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory != null ? Paths.get(workingDirectory) : null;
            return this;
        }

        public GlobTool build() {
            return new GlobTool(maxDepth, maxResults, workingDirectory);
        }

    }

}
