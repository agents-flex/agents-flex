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
package com.agentsflex.core.file2text.source;


import com.agentsflex.core.file2text.util.IOUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 HTTP/HTTPS URL 读取文档的输入源，支持缓存（避免重复请求）
 * 自动判断使用内存缓存还是临时文件缓存
 */
public class HttpDocumentSource implements DocumentSource {
    private static final int DEFAULT_CONNECT_TIMEOUT = 20_000;
    private static final int DEFAULT_READ_TIMEOUT = 60_000;
    private static final long MEMORY_THRESHOLD = 10 * 1024 * 1024; // 10MB 以内走内存

    private final String url;
    private final String providedFileName;
    private final String mimeType;
    private final int connectTimeout;
    private final int readTimeout;
    private final java.util.function.Consumer<HttpURLConnection> connectionCustomizer;

    private volatile byte[] cachedBytes = null;
    private volatile File tempFile = null;
    private volatile String resolvedFileName = null;
    private volatile String resolvedMimeType = null;
    private final AtomicBoolean downloaded = new AtomicBoolean(false);

    public HttpDocumentSource(String url) {
        this(url, null, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, null);
    }

    public HttpDocumentSource(String url, String fileName) {
        this(url, fileName, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, null);
    }

    public HttpDocumentSource(String url, String fileName, String mimeType) {
        this(url, fileName, mimeType, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, null);
    }

    public HttpDocumentSource(
        String url,
        String fileName,
        String mimeType,
        int connectTimeout,
        int readTimeout,
        java.util.function.Consumer<HttpURLConnection> connectionCustomizer
    ) {
        this.url = validateUrl(url);
        this.providedFileName = fileName;
        this.mimeType = mimeType;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.connectionCustomizer = connectionCustomizer;
    }

    private String validateUrl(String url) {
        try {
            new URL(url).toURI();
            return url;
        } catch (Exception e) {
            throw new RuntimeException("Invalid URL: " + url);
        }
    }

    @Override
    public String getFileName() {
        if (resolvedFileName != null) {
            return resolvedFileName;
        }
        synchronized (this) {
            if (resolvedFileName == null) {
                resolvedFileName = detectFileName();
            }
        }
        return resolvedFileName;
    }

    private String detectFileName() {
        // 1. 用户提供
        if (providedFileName != null && !providedFileName.trim().isEmpty()) {
            return sanitizeFileName(providedFileName);
        }

        // 2. 从 URL 路径提取
        String fromUrl = extractFileNameFromUrl();
        if (fromUrl != null) return fromUrl;

        // 3. 从 Content-Disposition 提取（需要连接）
        try {
            HttpURLConnection conn = createConnection();
            conn.setRequestMethod("HEAD"); // 只获取头
            conn.connect();
            String fromHeader = extractFileNameFromHeader(conn);
            conn.disconnect();
            if (fromHeader != null) return fromHeader;
        } catch (IOException e) {
            // 忽略
        }

        return "downloaded-file";
    }

    private String extractFileNameFromUrl() {
        try {
            URL urlObj = new URL(this.url);
            String path = urlObj.getPath();
            if (path != null && path.length() > 1) {
                String name = Paths.get(path).getFileName().toString();
                if (name.contains(".")) {
                    return sanitizeFileName(name);
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    private String extractFileNameFromHeader(HttpURLConnection conn) {
        try {
            String header = conn.getHeaderField("Content-Disposition");
            if (header != null) {
                Pattern pattern = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?");
                Matcher matcher = pattern.matcher(header);
                if (matcher.find()) {
                    return sanitizeFileName(matcher.group(1));
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    public static String sanitizeFileName(String filename) {
        if (filename == null) return "unknown";
        return filename
            .replaceAll("[\\\\/:*?\"<>|]", "_")
            .replaceAll("\\.\\.", "_")
            .replaceAll("^\\s+|\\s+$", "")
            .isEmpty() ? "file" : filename;
    }

    @Override
    public String getMimeType() {
        if (resolvedMimeType != null) {
            return resolvedMimeType;
        }
        synchronized (this) {
            if (resolvedMimeType == null) {
                try {
                    HttpURLConnection conn = createConnection();
                    conn.setRequestMethod("HEAD");
                    conn.connect();
                    resolvedMimeType = conn.getContentType();
                    conn.disconnect();
                } catch (IOException e) {
                    resolvedMimeType = mimeType; // fallback
                }
                if (resolvedMimeType == null) {
                    resolvedMimeType = mimeType;
                }
            }
        }
        return resolvedMimeType;
    }

    @Override
    public InputStream openStream() throws IOException {
        downloadIfNeeded();
        if (cachedBytes != null) {
            return new ByteArrayInputStream(cachedBytes);
        } else if (tempFile != null) {
            return new FileInputStream(tempFile);
        } else {
            throw new IOException("No content available");
        }
    }

    /**
     * 下载一次，缓存结果
     */
    private void downloadIfNeeded() throws IOException {
        if (downloaded.get()) return;

        synchronized (this) {
            if (downloaded.get()) return;

            HttpURLConnection conn = createConnection();
            conn.connect();

            try {
                int code = conn.getResponseCode();
                if (code >= 400) {
                    throw new IOException("HTTP " + code + " from " + url);
                }

                // 判断是否走内存 or 临时文件
                long contentLength = conn.getContentLengthLong();
                boolean useMemory = contentLength > 0 && contentLength <= MEMORY_THRESHOLD;

                if (useMemory) {
                    // 内存缓存
                    this.cachedBytes = IOUtils.toByteArray(conn.getInputStream(), MEMORY_THRESHOLD);
                } else {
                    // 临时文件缓存
                    this.tempFile = File.createTempFile("http-", ".cache");
                    this.tempFile.deleteOnExit();
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        IOUtils.copyStream(conn.getInputStream(), fos, Long.MAX_VALUE);
                    }
                }

                // 更新 MIME（如果未指定）
                if (this.resolvedMimeType == null) {
                    this.resolvedMimeType = conn.getContentType();
                }

            } finally {
                conn.disconnect();
            }

            downloaded.set(true);
        }
    }

    private HttpURLConnection createConnection() throws IOException {
        URL urlObj = new URL(this.url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "DocumentParser/1.0");
        if (connectionCustomizer != null) {
            connectionCustomizer.accept(conn);
        }
        return conn;
    }

    /**
     * 获取缓存大小（用于调试）
     */
    public long getCachedSize() {
        if (cachedBytes != null) return cachedBytes.length;
        if (tempFile != null) return tempFile.length();
        return 0;
    }

    /**
     * 清理临时文件
     */
    public void cleanup() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }
}
