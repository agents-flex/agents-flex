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
package com.agentsflex.skill.artifact.objectstorage;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.artifact.FileSystemSkillArtifactStore;
import com.agentsflex.skill.artifact.PathSkillPackage;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillArtifactStore;
import com.agentsflex.skill.artifact.SkillArtifactStoreException;
import com.agentsflex.skill.artifact.SkillInstallRequest;
import com.agentsflex.skill.artifact.SkillPackage;
import com.agentsflex.skill.util.Skills;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * 使用对象存储持久化 Skill Artifact，并按内容摘要缓存到当前节点。
 *
 * <p>对象存储中保存原始 ZIP 包；{@link #materialize(SkillArtifact)} 下载、校验 SHA-256 后，
 * 复用 {@link FileSystemSkillArtifactStore} 安全解压和原子发布。缓存目录按 digest 命名，
 * JVM 锁和文件锁共同避免同一节点及共享缓存目录中的重复下载。</p>
 */
public class ObjectStorageSkillArtifactStore implements SkillArtifactStore, AutoCloseable {

    private static final int COPY_BUFFER_SIZE = 8192;
    private static final int CACHE_LOCK_STRIPES = 64;
    public static final long DEFAULT_MAX_PACKAGE_SIZE = 512L * 1024L * 1024L;
    private static final Object[] CACHE_LOCKS = createCacheLocks();

    private final ObjectStorageOperations operations;
    private final String bucket;
    private final String keyPrefix;
    private final Path cacheDirectory;
    private final Path downloadDirectory;
    private final Path lockDirectory;
    private final FileSystemSkillArtifactStore cacheStore;
    private final long maxPackageSize;

    public ObjectStorageSkillArtifactStore(ObjectStorageOperations operations, String bucket,
                                           String keyPrefix, Path cacheDirectory) {
        this(operations, bucket, keyPrefix, cacheDirectory, DEFAULT_MAX_PACKAGE_SIZE);
    }

    public ObjectStorageSkillArtifactStore(ObjectStorageOperations operations, String bucket, String keyPrefix,
                                           Path cacheDirectory, long maxPackageSize) {
        if (operations == null) {
            throw new IllegalArgumentException("operations must not be null");
        }
        if (bucket == null || bucket.trim().isEmpty()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (cacheDirectory == null) {
            throw new IllegalArgumentException("cacheDirectory must not be null");
        }
        if (maxPackageSize <= 0) {
            throw new IllegalArgumentException("maxPackageSize must be greater than zero");
        }
        this.operations = operations;
        this.bucket = bucket.trim();
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.cacheDirectory = createDirectory(cacheDirectory).resolve("objects");
        this.downloadDirectory = createDirectory(cacheDirectory.resolve("downloads"));
        this.lockDirectory = createDirectory(cacheDirectory.resolve("locks"));
        createDirectory(this.cacheDirectory);
        this.cacheStore = new FileSystemSkillArtifactStore(this.cacheDirectory);
        this.maxPackageSize = maxPackageSize;
    }

    @Override
    public SkillArtifact install(SkillInstallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        SkillArtifact artifact = requireArtifact(request.getArtifact());
        if (artifact.getName() == null || artifact.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("artifact name must not be blank");
        }
        SkillPackage skillPackage = request.getSkillPackage();
        if (skillPackage == null) {
            throw new IllegalArgumentException("request skillPackage must not be null");
        }
        requireZipPackage(skillPackage);

        Path staged = null;
        try {
            staged = Files.createTempFile(downloadDirectory, "install-", ".zip");
            DigestCopy stagedCopy = copyAndDigest(skillPackage.openStream(), staged, maxPackageSize);
            long declaredSize = skillPackage.getSize();
            if (declaredSize >= 0 && declaredSize != stagedCopy.size) {
                throw new SkillArtifactStoreException("Skill package size changed while installing");
            }

            String digest = verifiedDigest(artifact.getDigest(), stagedCopy.digest);
            validatePackage(staged, artifact.getName());
            String objectKey = artifact.getStorageKey();
            if (objectKey == null || objectKey.trim().isEmpty()) {
                objectKey = generatedObjectKey(artifact, digest);
            } else {
                objectKey = normalizeObjectKey(objectKey);
            }

            try (InputStream input = Files.newInputStream(staged)) {
                operations.put(bucket, objectKey, input, stagedCopy.size);
            }
            return copyArtifact(artifact, digest, objectKey, stagedCopy.size);
        } catch (SkillArtifactStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillArtifactStoreException("Failed to install skill artifact in object storage", e);
        } finally {
            deleteQuietly(staged);
        }
    }

    @Override
    public Path materialize(SkillArtifact artifact) {
        SkillArtifact installed = requireArtifact(artifact);
        String objectKey = normalizeObjectKey(installed.getStorageKey());
        String digestHex = requiredDigestHex(installed.getDigest());
        final SkillArtifact cached = cacheArtifact(installed, digestHex);
        Path existing = cachedPath(digestHex);
        if (Files.isDirectory(existing)) {
            return cacheStore.materialize(cached);
        }

        String lockKey = cacheDirectory.toString() + ":" + digestHex;
        Object monitor = CACHE_LOCKS[(lockKey.hashCode() & Integer.MAX_VALUE) % CACHE_LOCKS.length];
        synchronized (monitor) {
            return materializeLocked(installed, cached, objectKey, digestHex);
        }
    }

    private Path materializeLocked(SkillArtifact artifact, SkillArtifact cached,
                                   String objectKey, String digestHex) {
        Path lockPath = lockDirectory.resolve(digestHex + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Path existing = cachedPath(digestHex);
            if (Files.isDirectory(existing)) {
                return cacheStore.materialize(cached);
            }

            Path downloaded = Files.createTempFile(downloadDirectory, "download-", ".zip");
            try {
                DigestCopy copy;
                try (InputStream input = operations.get(bucket, objectKey)) {
                    copy = copyAndDigest(input, downloaded, maxPackageSize);
                }
                if (!digestHex.equals(copy.digest)) {
                    throw new SkillArtifactStoreException("Downloaded object artifact digest does not match: "
                        + artifact.getDigest());
                }
                if (artifact.getSize() > 0 && artifact.getSize() != copy.size) {
                    throw new SkillArtifactStoreException("Downloaded object artifact size does not match: "
                        + artifact.getSize());
                }
                cacheStore.install(new SkillInstallRequest(cached, new PathSkillPackage(downloaded)));
                return cacheStore.materialize(cached);
            } finally {
                deleteQuietly(downloaded);
            }
        } catch (SkillArtifactStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillArtifactStoreException("Failed to materialize object storage artifact: " + objectKey, e);
        }
    }

    @Override
    public void delete(SkillArtifact artifact) {
        SkillArtifact installed = requireArtifact(artifact);
        String objectKey = normalizeObjectKey(installed.getStorageKey());
        try {
            operations.delete(bucket, objectKey);
            if (installed.getDigest() != null && !installed.getDigest().trim().isEmpty()) {
                String digestHex = requiredDigestHex(installed.getDigest());
                cacheStore.delete(cacheArtifact(installed, digestHex));
            }
        } catch (SkillArtifactStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillArtifactStoreException("Failed to delete object storage artifact: " + objectKey, e);
        }
    }

    @Override
    public void close() {
        operations.close();
    }

    private Path cachedPath(String digestHex) {
        return cacheDirectory.resolve(digestHex);
    }

    private void validatePackage(Path skillPackage, String expectedName) throws IOException {
        Path validationRoot = Files.createTempDirectory(downloadDirectory, "validate-");
        FileSystemSkillArtifactStore validationStore = new FileSystemSkillArtifactStore(validationRoot);
        SkillArtifact validationArtifact = new SkillArtifact(expectedName, null, null, "skill");
        try {
            validationStore.install(new SkillInstallRequest(
                validationArtifact, new PathSkillPackage(skillPackage)));
            Path skillDirectory = validationStore.materialize(validationArtifact);
            List<Skill> definitions = Skills.loadDirectory(skillDirectory.toString());
            if (definitions.size() != 1 || !expectedName.equals(definitions.get(0).name())) {
                throw new SkillArtifactStoreException(
                    "Skill package must contain exactly one matching SKILL.md: " + expectedName);
            }
        } finally {
            try {
                validationStore.delete(validationArtifact);
            } finally {
                Files.deleteIfExists(validationRoot);
            }
        }
    }

    private SkillArtifact cacheArtifact(SkillArtifact artifact, String digestHex) {
        return new SkillArtifact(artifact.getName(), artifact.getVersion(), artifact.getDigest(),
            digestHex, artifact.getSize());
    }

    private String generatedObjectKey(SkillArtifact artifact, String digest) {
        StringBuilder key = new StringBuilder();
        if (!keyPrefix.isEmpty()) {
            key.append(keyPrefix).append('/');
        }
        key.append(safeSegment(artifact.getName(), "skill"))
            .append('/').append(safeSegment(artifact.getVersion(), "unversioned"))
            .append('/').append(requiredDigestHex(digest)).append(".zip");
        return key.toString();
    }

    private static DigestCopy copyAndDigest(InputStream input, Path target, long maxSize) throws IOException {
        if (input == null) {
            throw new SkillArtifactStoreException("SkillPackage or object storage returned a null stream");
        }
        MessageDigest digest = sha256();
        long size = 0L;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream source = input; OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                size += read;
                if (size > maxSize) {
                    throw new SkillArtifactStoreException("Skill package exceeds maximum size: " + maxSize);
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new DigestCopy(hex(digest.digest()), size);
    }

    private static String verifiedDigest(String declaredDigest, String actualHex) {
        if (declaredDigest == null || declaredDigest.trim().isEmpty()) {
            return "sha256:" + actualHex;
        }
        String declaredHex = requiredDigestHex(declaredDigest);
        if (!declaredHex.equals(actualHex)) {
            throw new SkillArtifactStoreException("Skill package digest does not match: " + declaredDigest);
        }
        return "sha256:" + actualHex;
    }

    private static String requiredDigestHex(String digest) {
        if (digest == null) {
            throw new SkillArtifactStoreException("Skill artifact digest must be a SHA-256 value");
        }
        String value = digest.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("sha256:")) {
            value = value.substring("sha256:".length());
        } else if (value.startsWith("sha256-")) {
            value = value.substring("sha256-".length());
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new SkillArtifactStoreException("Skill artifact digest must be a SHA-256 value: " + digest);
        }
        return value;
    }

    private static String normalizeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.trim().isEmpty()) {
            throw new SkillArtifactStoreException("Skill artifact storageKey must not be blank");
        }
        String value = objectKey.trim();
        if (value.startsWith("/") || value.indexOf('\\') >= 0) {
            throw new SkillArtifactStoreException("Invalid object storage key: " + objectKey);
        }
        return value;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return "";
        }
        String value = prefix.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("keyPrefix must use forward slashes");
        }
        return value;
    }

    private static String safeSegment(String value, String fallback) {
        String segment = value == null ? fallback : value.replaceAll("[^a-zA-Z0-9._-]", "-");
        return segment.isEmpty() ? fallback : segment;
    }

    private static void requireZipPackage(SkillPackage skillPackage) {
        String fileName = skillPackage.getFileName();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new SkillArtifactStoreException("Object storage skill installation requires a ZIP package");
        }
    }

    private static SkillArtifact requireArtifact(SkillArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact must not be null");
        }
        return artifact;
    }

    private static Path createDirectory(Path directory) {
        try {
            return Files.createDirectories(directory).toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to create cache directory: " + directory, e);
        }
    }

    private static SkillArtifact copyArtifact(SkillArtifact source, String digest, String storageKey, long size) {
        return new SkillArtifact(source.getName(), source.getVersion(), digest, storageKey, size);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static Object[] createCacheLocks() {
        Object[] locks = new Object[CACHE_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the primary operation result; stale staging files can be cleaned independently.
        }
    }

    private static class DigestCopy {
        private final String digest;
        private final long size;

        private DigestCopy(String digest, long size) {
            this.digest = digest;
            this.size = size;
        }
    }

}
