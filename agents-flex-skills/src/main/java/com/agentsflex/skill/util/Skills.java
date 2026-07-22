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
 * Skill 发现与加载工具。
 *
 * <p>当前公开入口从一个或多个文件系统根目录递归查找 {@code SKILL.md}，解析 front
 * matter 和正文，并把 {@code SKILL.md} 的父目录记录为 Skill 根目录。加载阶段只读取
 * 描述文件，不执行任何脚本。</p>
 *
 * @author Christian Tzolov
 * @author Micahel Yang
 */

public class Skills {


    /**
     * 从多个根目录加载全部 Skills。
     *
     * @param rootDirectories Skills 根目录列表
     * @return 按目录依次合并的 Skill 列表
     */
    public static List<Skill> loadDirectories(List<String> rootDirectories) {
        List<Skill> skills = new ArrayList<>();
        for (String rootDirectory : rootDirectories) {
            skills.addAll(loadDirectory(rootDirectory));
        }
        return skills;
    }

    /**
     * 递归查找指定目录下的全部 {@code SKILL.md} 并解析为 Skill。
     *
     * @param rootDirectory 用于搜索 {@code SKILL.md} 的根目录
     * @return 包含根路径、front matter 和正文的 Skill 列表
     * @throws RuntimeException 根目录不存在、不是目录或读取失败
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
                    try (InputStream inputStream = Files.newInputStream(path)) {
                        String markdown = IOUtil.readUtf8(inputStream);
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
     * 扫描 classpath JAR 中指定前缀下的 {@code SKILL.md}。
     *
     * <p>通过枚举 {@code META-INF/MANIFEST.MF} 定位 JAR，适用于普通 classpath 资源
     * 枚举无法返回目录条目的场景。</p>
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
     * 扫描单个 JAR 内指定前缀下的 Skill 定义。
     *
     * @param jarFile 待扫描 JAR
     * @param entryPrefix 匹配前缀，必须以 {@code /} 结尾
     * @return 在 JAR 中发现的 Skill 列表
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
     * 从输入流解析一个 {@code SKILL.md}。
     *
     * @param is Markdown 内容输入流
     * @param entryPath JAR 条目路径，用于推导 Skill 根目录
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
     * 从资源 URL 中去掉 {@code SKILL.md} 和 {@code jar:file:...!/} 前缀，得到 JAR 内路径。
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
