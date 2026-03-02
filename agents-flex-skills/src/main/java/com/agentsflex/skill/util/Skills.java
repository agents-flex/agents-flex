/*
 * Copyright 2026 - 2026 the original author or authors.
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
package com.agentsflex.skill.util;


import com.agentsflex.core.util.IOUtil;
import com.agentsflex.skill.Skill;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * @author Christian Tzolov
 * @author Micahel Yang
 */

public class Skills {


    public static List<Skill> loadDirectories(List<String> rootDirectories) {
        List<Skill> skills = new ArrayList<>();
        for (String rootDirectory : rootDirectories) {
            skills.addAll(loadDirectory(rootDirectory));
        }
        return skills;
    }

    /**
     * Recursively finds all SKILL.md files in the given root directory and returns their
     * parsed contents.
     *
     * @param rootDirectory the root directory to search for SKILL.md files
     * @return a list of Skill objects containing the basePath, front-matter, and content
     * of each SKILL.md file
     * @throws RuntimeException if an I/O error occurs while reading the directory or
     *                          files
     */
    public static List<Skill> loadDirectory(String rootDirectory) {

        Path rootPath = Paths.get(rootDirectory);

        if (!Files.exists(rootPath)) {
            throw new RuntimeException("Root directory does not exist: " + rootDirectory);
        }

        if (!Files.isDirectory(rootPath)) {
            throw new RuntimeException("Path is not a directory: " + rootDirectory);
        }

        List<Skill> skills = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                .forEach(path -> {
                    try {
                        String markdown = IOUtil.readUtf8(Files.newInputStream(path));
                        MarkdownParser parser = new MarkdownParser(markdown);
                        skills.add(new Skill(path.getParent().toString(), parser.getFrontMatter(),
                            parser.getContent()));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read SKILL.md file: " + path, e);
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk root directory: " + rootDirectory, e);
        }

        return skills;
    }


    /**
     * Scans all classpath JARs for SKILL.md files under the given prefix. Discovers JARs
     * via {@code ClassLoader.getResources("META-INF/MANIFEST.MF")} — a technique used by
     * Spring internally when standard classpath resolution is insufficient.
     */
    private static List<Skill> scanClasspathJarsForSkills(String classpathPrefix) throws IOException {
        String prefix = classpathPrefix.endsWith("/") ? classpathPrefix : classpathPrefix + "/";

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = Skills.class.getClassLoader();
        }

        List<Skill> skills = new ArrayList<>();

        Enumeration<URL> manifests = classLoader.getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            URL manifestUrl = manifests.nextElement();
            if (!"jar".equals(manifestUrl.getProtocol())) {
                continue;
            }

            JarURLConnection jarConnection = (JarURLConnection) manifestUrl.openConnection();
            skills.addAll(scanJarForSkills(jarConnection.getJarFile(), prefix));
        }

        return skills;
    }

    /**
     * Scans a single JAR file for SKILL.md entries under the given prefix.
     *
     * @param jarFile     the JAR to scan
     * @param entryPrefix the entry prefix to match (must end with '/')
     * @return a list of Skill objects found in this JAR
     */
    private static List<Skill> scanJarForSkills(JarFile jarFile, String entryPrefix) throws IOException {
        List<Skill> skills = new ArrayList<>();
        Enumeration<JarEntry> entries = jarFile.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            if (!entry.isDirectory() && entryName.startsWith(entryPrefix)
                && entryName.endsWith("/SKILL.md")) {
                try (InputStream is = jarFile.getInputStream(entry)) {
                    skills.add(parseSkill(is, entryName));
                }
            }
        }
        return skills;
    }

    /**
     * Parses a SKILL.md file from an input stream into a {@link Skill}.
     *
     * @param is        the input stream containing the SKILL.md markdown content
     * @param entryPath the JAR entry path — used to derive the base directory
     */
    private static Skill parseSkill(InputStream is, String entryPath) throws IOException {
        String markdown = IOUtil.readUtf8(is);
        MarkdownParser parser = new MarkdownParser(markdown);
        String basePath = entryPath.endsWith("/SKILL.md")
            ? entryPath.substring(0, entryPath.lastIndexOf('/'))
            : entryPath;
        return new Skill(basePath, parser.getFrontMatter(), parser.getContent());
    }

    /**
     * Derives the JAR-internal base path from a resource URL by stripping the SKILL.md
     * filename and the {@code jar:file:...!/} prefix.
     */
    private static String deriveBasePathFromUrl(URL skillUrl) {
        String urlStr = skillUrl.toString();
        String basePath = urlStr.substring(0, urlStr.lastIndexOf("/SKILL.md"));
        if (basePath.contains("!/")) {
            basePath = basePath.substring(basePath.indexOf("!/") + 2);
        }
        return basePath;
    }

}
