/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 与传输协议无关的 Skill Runtime 文件系统边界。
 *
 * <p>本接口既服务于模型可调用的 Read、Write、Edit、Glob、Grep 工具，也服务于业务代码
 * 对运行产物的回收。文本方法适合模型上下文，二进制流和 {@code download} 方法适合
 * PPTX、PDF、图片、压缩包等不能按 UTF-8 文本处理的文件。</p>
 *
 * <p>所有 {@code path} 都是 Runtime 内路径。只有 {@link #download(String, Path)} 的
 * {@code destination} 是宿主机本地路径。</p>
 */
public interface SkillRuntimeFileSystem {

    /**
     * 打开 Runtime 文件的二进制输入流。
     *
     * <p>实现可以返回本地文件流，也可以返回远程 HTTP 响应流。调用方必须关闭返回流，
     * 关闭动作同时负责释放底层文件句柄或网络连接。</p>
     *
     * @param path Runtime 内文件路径
     * @return 可流式读取文件内容的输入流
     * @throws SkillRuntimeException 当前实现不支持二进制读取或打开文件失败
     */
    default InputStream openInputStream(String path) {
        throw new SkillRuntimeException("Runtime filesystem does not support binary reads: " + path);
    }

    /**
     * 将 Runtime 文件完整读取到内存，并强制限制最大字节数。
     *
     * <p>该方法适合小型二进制文件。大型产物应使用 {@link #download(String, OutputStream)}，
     * 避免占用与文件大小相同的堆内存。</p>
     *
     * @param path Runtime 内文件路径
     * @param maxBytes 允许读取的最大字节数，必须大于 0
     * @return 文件的完整二进制内容
     * @throws SkillRuntimeException 文件超过限制或读取失败
     */
    default byte[] readBytes(String path, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        try (InputStream input = openInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new SkillRuntimeException("File exceeds read limit of " + maxBytes + " bytes: " + path);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (SkillRuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to read runtime file: " + path, e);
        }
    }

    /**
     * 将 Runtime 文件流式写入调用方提供的输出流。
     *
     * <p>本方法会关闭源输入流，但不会关闭 {@code destination}，便于调用方把产物上传到
     * 对象存储、HTTP 响应或其他第三方文件中心后继续使用目标流。</p>
     *
     * @param path Runtime 内文件路径
     * @param destination 调用方持有的目标流，不能为 {@code null}
     */
    default void download(String path, OutputStream destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        try (InputStream input = openInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                destination.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to download runtime file: " + path, e);
        }
    }

    /**
     * 将 Runtime 文件下载到指定的宿主机路径。
     *
     * <p>实现先写入同目录临时文件，成功后再原子替换目标文件；文件系统不支持原子移动时
     * 自动降级为普通替换。这样可以避免下载中断时留下看似完整的目标文件。</p>
     *
     * @param path Runtime 内文件路径
     * @param destination 宿主机上的最终文件路径
     * @return 规范化后的宿主机绝对路径
     */
    default Path download(String path, Path destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        Path localFile = destination.toAbsolutePath().normalize();
        Path temporaryFile = null;
        try {
            Path parent = localFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temporaryFile = Files.createTempFile(parent, localFile.getFileName().toString() + ".", ".part");
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                download(path, output);
            }
            try {
                Files.move(temporaryFile, localFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, localFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return localFile;
        } catch (SkillRuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to save runtime file to: " + localFile, e);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // 清理失败不能覆盖真正的下载结果或原始异常。
                }
            }
        }
    }

    /**
     * 按 UTF-8 文本读取 Runtime 文件。
     *
     * @param path Runtime 内文件路径
     * @param maxBytes 最大读取字节数
     * @return 文件文本
     * @throws SkillRuntimeException 文件不存在、超过限制或读取失败
     */
    String readText(String path, int maxBytes);

    /**
     * 将 UTF-8 文本写入 Runtime 文件，已有文件会被覆盖。
     *
     * @param path Runtime 内文件路径
     * @param content 文本内容
     */
    void writeText(String path, String content);

    /**
     * 查询 Runtime 路径的元数据。
     *
     * @param path Runtime 内路径
     * @return 文件元数据；不存在时返回 {@code null}
     */
    SkillFileInfo stat(String path);

    /**
     * 递归列出 Runtime 目录内容。
     *
     * @param path 起始目录
     * @param maxDepth 最大递归深度
     * @param maxResults 最大返回条数，用于防止大目录耗尽内存或模型上下文
     * @return 文件和目录元数据列表
     */
    List<SkillFileInfo> listFiles(String path, int maxDepth, int maxResults);
}
