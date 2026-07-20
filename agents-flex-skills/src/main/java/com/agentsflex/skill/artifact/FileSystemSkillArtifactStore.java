/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 将指定安装根目录下的子目录作为 Skill Artifact 的文件系统实现。
 *
 * <p>{@link SkillArtifact#getStorageKey()} 必须是安装根目录内的相对路径。实现会阻止绝对
 * 路径和 {@code ..} 路径逃逸，并验证目标目录直接包含 {@code SKILL.md}。</p>
 */
public class FileSystemSkillArtifactStore implements SkillArtifactStore {

    private static final int COPY_BUFFER_SIZE = 8192;
    private static final int MAX_ARCHIVE_ENTRIES = 10000;
    private static final long MAX_UNCOMPRESSED_SIZE = 512L * 1024L * 1024L;

    private final Path rootDirectory;

    public FileSystemSkillArtifactStore(Path rootDirectory) {
        if (rootDirectory == null) {
            throw new IllegalArgumentException("rootDirectory must not be null");
        }
        if (!Files.isDirectory(rootDirectory)) {
            throw new IllegalArgumentException("rootDirectory must be an existing directory: " + rootDirectory);
        }
        try {
            this.rootDirectory = rootDirectory.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve rootDirectory: " + rootDirectory, e);
        }
    }

    @Override
    public SkillArtifact install(SkillInstallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        SkillArtifact artifact = request.getArtifact();
        requireArtifact(artifact);
        SkillPackage skillPackage = request.getSkillPackage();
        if (skillPackage == null) {
            throw new IllegalArgumentException("request skillPackage must not be null");
        }
        String fileName = skillPackage.getFileName();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new SkillArtifactStoreException("File-system skill installation requires a ZIP package");
        }

        String storageKey = requireStorageKey(artifact);
        Path destination = resolveStoragePath(storageKey);
        Path parent = destination.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(rootDirectory)) {
                throw new SkillArtifactStoreException(
                    "Skill artifact destination resolves outside the configured root: " + storageKey);
            }
            destination = realParent.resolve(destination.getFileName());
            if (Files.exists(destination)) {
                throw new SkillArtifactStoreException("Skill artifact already exists: " + destination);
            }

            temporary = realParent.resolve("." + destination.getFileName() + ".install-" + UUID.randomUUID());
            extractZip(skillPackage, temporary);
            requireSkillDefinition(temporary, storageKey);
            moveAtomically(temporary, destination);
            temporary = null;
            return artifact;
        } catch (SkillArtifactStoreException e) {
            throw e;
        } catch (IOException e) {
            throw new SkillArtifactStoreException("Failed to install skill artifact: " + storageKey, e);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    @Override
    public Path materialize(SkillArtifact artifact) {
        requireArtifact(artifact);
        String storageKey = requireStorageKey(artifact);
        Path directory = resolveStoragePath(storageKey);
        if (!Files.isDirectory(directory)) {
            throw new SkillArtifactStoreException("Skill artifact directory does not exist: " + directory);
        }
        try {
            directory = directory.toRealPath();
        } catch (IOException e) {
            throw new SkillArtifactStoreException("Failed to resolve skill artifact directory: " + directory, e);
        }
        if (!directory.startsWith(rootDirectory)) {
            throw new SkillArtifactStoreException("Skill artifact resolves outside the configured root: " + storageKey);
        }
        requireSkillDefinition(directory, storageKey);
        return directory;
    }

    @Override
    public void delete(SkillArtifact artifact) {
        requireArtifact(artifact);
        String storageKey = requireStorageKey(artifact);
        Path directory = resolveStoragePath(storageKey);
        if (!Files.exists(directory)) {
            return;
        }
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new SkillArtifactStoreException(
                    "Skill artifact directory must not be a symbolic link: " + storageKey);
            }
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(rootDirectory) || realDirectory.equals(rootDirectory)) {
                throw new SkillArtifactStoreException(
                    "Skill artifact resolves outside the configured root: " + storageKey);
            }
            deleteDirectory(realDirectory);
        } catch (SkillArtifactStoreException e) {
            throw e;
        } catch (IOException e) {
            throw new SkillArtifactStoreException("Failed to delete skill artifact: " + storageKey, e);
        }
    }

    private void requireArtifact(SkillArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact must not be null");
        }
    }

    private String requireStorageKey(SkillArtifact artifact) {
        String storageKey = artifact.getStorageKey();
        if (storageKey == null || storageKey.trim().isEmpty()) {
            throw new SkillArtifactStoreException("Skill artifact storageKey must not be blank");
        }
        return storageKey;
    }

    private Path resolveStoragePath(String storageKey) {
        Path relativePath;
        try {
            relativePath = rootDirectory.getFileSystem().getPath(storageKey);
        } catch (RuntimeException e) {
            throw new SkillArtifactStoreException("Invalid skill artifact storageKey: " + storageKey, e);
        }
        if (relativePath.isAbsolute()) {
            throw new SkillArtifactStoreException("Skill artifact storageKey must be relative: " + storageKey);
        }

        Path path = rootDirectory.resolve(relativePath).normalize();
        if (path.equals(rootDirectory) || !path.startsWith(rootDirectory)) {
            throw new SkillArtifactStoreException("Skill artifact escapes the configured root: " + storageKey);
        }
        return path;
    }

    private void extractZip(SkillPackage skillPackage, Path destination) throws IOException {
        Files.createDirectory(destination);
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int entryCount = 0;
        long totalSize = 0L;

        InputStream openedStream = skillPackage.openStream();
        if (openedStream == null) {
            throw new SkillArtifactStoreException("SkillPackage.openStream must not return null");
        }
        try (InputStream stream = openedStream; ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new SkillArtifactStoreException(
                        "Skill package exceeds maximum entry count: " + MAX_ARCHIVE_ENTRIES);
                }
                Path target = zipEntryPath(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (Files.exists(target)) {
                        throw new SkillArtifactStoreException("Duplicate skill package entry: " + entry.getName());
                    }
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            totalSize += read;
                            if (totalSize > MAX_UNCOMPRESSED_SIZE) {
                                throw new SkillArtifactStoreException(
                                    "Skill package exceeds maximum uncompressed size: " + MAX_UNCOMPRESSED_SIZE);
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private Path zipEntryPath(Path destination, String entryName) {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\\') >= 0) {
            throw new SkillArtifactStoreException("Invalid skill package entry: " + entryName);
        }
        Path relative;
        try {
            relative = destination.getFileSystem().getPath(entryName).normalize();
        } catch (RuntimeException e) {
            throw new SkillArtifactStoreException("Invalid skill package entry: " + entryName, e);
        }
        Path target = destination.resolve(relative).normalize();
        if (relative.isAbsolute() || target.equals(destination) || !target.startsWith(destination)) {
            throw new SkillArtifactStoreException("Skill package entry escapes target directory: " + entryName);
        }
        return target;
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        } catch (FileAlreadyExistsException e) {
            throw new SkillArtifactStoreException("Skill artifact already exists: " + destination, e);
        }
    }

    private void deleteDirectory(final Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteQuietly(Path directory) {
        try {
            if (Files.exists(directory)) {
                deleteDirectory(directory);
            }
        } catch (IOException ignored) {
            // Preserve the installation failure; abandoned temporary directories can be cleaned later.
        }
    }

    private void requireSkillDefinition(Path directory, String storageKey) {
        Path skillDefinition = directory.resolve("SKILL.md");
        if (!Files.isRegularFile(skillDefinition)) {
            throw new SkillArtifactStoreException("Skill artifact does not contain SKILL.md: " + directory);
        }
        try {
            if (!skillDefinition.toRealPath().startsWith(rootDirectory)) {
                throw new SkillArtifactStoreException(
                    "Skill artifact SKILL.md resolves outside the configured root: " + storageKey);
            }
        } catch (IOException e) {
            throw new SkillArtifactStoreException("Failed to resolve SKILL.md: " + skillDefinition, e);
        }
    }
}
