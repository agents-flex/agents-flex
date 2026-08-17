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
package com.agentsflex.core.model.ocr;

import com.agentsflex.core.document.ExtractedImageHandler;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.StringUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 将统一 OCR 响应中的内联内容或下载资源物化为 Markdown。
 */
public class OcrMarkdownResolver {
    private static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("(!\\[[^\\]]*\\]\\()(<[^>]+>|[^\\s)]+)([^)]*\\))");
    private static final OcrMarkdownResolver DEFAULT =
        new OcrMarkdownResolver(OkHttpClientUtil.buildDefaultClient());

    private final OkHttpClient httpClient;

    public OcrMarkdownResolver(OkHttpClient httpClient) {
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    public static OcrMarkdownResolver getDefault() {
        return DEFAULT;
    }

    public String resolve(OcrResponse response) {
        return resolve(response, null);
    }

    /**
     * 依次尝试内联 Markdown、Markdown 下载资源和包含 Markdown 的 ZIP 资源。
     */
    public String resolve(OcrResponse response, ExtractedImageHandler imageHandler) {
        validate(response);
        if (StringUtil.hasText(response.getMarkdown())) {
            return rewriteImages(response.getMarkdown(), null, null, imageHandler);
        }
        OcrResource markdown = findResource(response, "markdown");
        if (markdown != null) {
            byte[] bytes = download(markdown.getUrl());
            return rewriteImages(new String(bytes, StandardCharsets.UTF_8), markdown.getUrl(), null, imageHandler);
        }
        OcrResource archive = findResource(response, "archive");
        if (archive != null) return resolveArchive(download(archive.getUrl()), imageHandler);
        if (StringUtil.hasText(response.getText())) return response.getText();
        throw new OcrMarkdownResolveException("OCR response does not contain Markdown or a supported Markdown resource");
    }

    private static void validate(OcrResponse response) {
        if (response == null) throw new OcrMarkdownResolveException("OCR response must not be null");
        if (response.isError()) {
            throw new OcrMarkdownResolveException("OCR request failed" +
                (StringUtil.hasText(response.getErrorMessage()) ? ": " + response.getErrorMessage() : ""));
        }
    }

    private static OcrResource findResource(OcrResponse response, String type) {
        for (OcrResource resource : response.getResources()) {
            if (resource != null && type.equalsIgnoreCase(resource.getType()) && StringUtil.hasText(resource.getUrl())) {
                return resource;
            }
        }
        return null;
    }

    private byte[] download(String url) {
        Request request;
        try {
            request = new Request.Builder().url(url).get().build();
        } catch (IllegalArgumentException e) {
            throw new OcrMarkdownResolveException("Invalid OCR resource URL: " + url, e);
        }
        try (Response response = httpClient.newCall(request).execute(); ResponseBody body = response.body()) {
            if (!response.isSuccessful()) {
                throw new OcrMarkdownResolveException("Failed to download OCR resource: HTTP " + response.code());
            }
            if (body == null) throw new OcrMarkdownResolveException("OCR resource response is empty");
            long contentLength = body.contentLength();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw new OcrMarkdownResolveException("OCR resource exceeds the maximum download size");
            }
            return readLimited(body.byteStream(), MAX_DOWNLOAD_BYTES, "OCR resource exceeds the maximum download size");
        } catch (IOException e) {
            throw new OcrMarkdownResolveException("Failed to download OCR resource", e);
        }
    }

    private String resolveArchive(byte[] archiveBytes, ExtractedImageHandler imageHandler) {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++count > MAX_ARCHIVE_ENTRIES) {
                    throw new OcrMarkdownResolveException("OCR archive contains too many entries");
                }
                String name = normalizeArchivePath(entry.getName());
                byte[] bytes = readLimited(zip, MAX_EXTRACTED_BYTES - total,
                    "OCR archive exceeds the maximum extracted size");
                total += bytes.length;
                entries.put(name, bytes);
            }
        } catch (IOException e) {
            throw new OcrMarkdownResolveException("Failed to read OCR archive", e);
        }
        String markdownPath = selectMarkdown(entries);
        if (markdownPath == null) throw new OcrMarkdownResolveException("OCR archive does not contain a Markdown file");
        String markdown = new String(entries.get(markdownPath), StandardCharsets.UTF_8);
        return rewriteImages(markdown, markdownPath, entries, imageHandler);
    }

    private String rewriteImages(String markdown, String base, Map<String, byte[]> archiveEntries,
                                 ExtractedImageHandler imageHandler) {
        if (StringUtil.noText(markdown)) return markdown;
        Matcher matcher = MARKDOWN_IMAGE.matcher(markdown);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String reference = stripAngles(matcher.group(2));
            if (imageHandler == null) {
                String absolute = archiveEntries == null ? resolveRemoteReference(base, reference) : null;
                if (absolute != null && !absolute.equals(reference)) {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(
                        matcher.group(1) + absolute + matcher.group(3)));
                }
                continue;
            }
            ImageData image = loadImage(reference, base, archiveEntries);
            if (image == null) {
                String absolute = archiveEntries == null ? resolveRemoteReference(base, reference) : null;
                if (absolute != null && !absolute.equals(reference)) {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(
                        matcher.group(1) + absolute + matcher.group(3)));
                }
                continue;
            }
            String replacement;
            try {
                String url = imageHandler.handle(image.bytes, mimeType(image.fileName), image.fileName);
                replacement = StringUtil.hasText(url)
                    ? matcher.group(1) + url + matcher.group(3) : "";
            } catch (IOException e) {
                throw new OcrMarkdownResolveException("Failed to handle OCR image: " + image.fileName, e);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String resolveRemoteReference(String base, String reference) {
        if (base == null) return null;
        try {
            URI uri = URI.create(base).resolve(reference);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ImageData loadImage(String reference, String base, Map<String, byte[]> archiveEntries) {
        if (archiveEntries != null) {
            String path = resolveArchivePath(base, reference);
            byte[] bytes = archiveEntries.get(path);
            return bytes == null ? null : new ImageData(bytes, fileName(path));
        }
        if (reference.startsWith("data:image/") && reference.contains(";base64,")) {
            int separator = reference.indexOf(";base64,");
            String mimeType = reference.substring(5, separator);
            try {
                byte[] bytes = Base64.getDecoder().decode(reference.substring(separator + 8));
                return new ImageData(bytes, "ocr-image." + extension(mimeType));
            } catch (IllegalArgumentException e) {
                throw new OcrMarkdownResolveException("Invalid Base64 image in OCR Markdown", e);
            }
        }
        String remoteUrl = resolveRemoteReference(base, reference);
        if (remoteUrl == null) remoteUrl = absoluteRemoteReference(reference);
        if (remoteUrl != null) {
            return new ImageData(download(remoteUrl), remoteFileName(remoteUrl));
        }
        return null;
    }

    private static String absoluteRemoteReference(String reference) {
        try {
            URI uri = URI.create(reference);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String remoteFileName(String url) {
        try {
            return fileName(URI.create(url).getPath());
        } catch (IllegalArgumentException e) {
            return "ocr-image";
        }
    }

    private static String selectMarkdown(Map<String, byte[]> entries) {
        String selected = null;
        for (String name : entries.keySet()) {
            if (!name.toLowerCase().endsWith(".md")) continue;
            if ("full.md".equalsIgnoreCase(fileName(name))) return name;
            if (selected == null || name.length() < selected.length() ||
                (name.length() == selected.length() && name.compareTo(selected) < 0)) selected = name;
        }
        return selected;
    }

    private static String resolveArchivePath(String markdownPath, String reference) {
        String clean = reference;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int slash = markdownPath.lastIndexOf('/');
        String parent = slash < 0 ? "" : markdownPath.substring(0, slash + 1);
        return normalizeArchivePath(parent + clean);
    }

    private static String normalizeArchivePath(String path) {
        try {
            String normalized = URI.create("file:///" + path.replace("\\", "/")).normalize().getPath();
            while (normalized.startsWith("/")) normalized = normalized.substring(1);
            return normalized;
        } catch (IllegalArgumentException e) {
            return path.replace("\\", "/");
        }
    }

    private static byte[] readLimited(java.io.InputStream input, long limit, String errorMessage) throws IOException {
        if (limit < 0) throw new OcrMarkdownResolveException(errorMessage);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new OcrMarkdownResolveException(errorMessage);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String stripAngles(String value) {
        return value.length() > 1 && value.charAt(0) == '<' && value.charAt(value.length() - 1) == '>'
            ? value.substring(1, value.length() - 1) : value;
    }

    private static String mimeType(String fileName) {
        String mime = URLConnection.guessContentTypeFromName(fileName);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String extension(String mimeType) {
        int slash = mimeType.indexOf('/');
        if (slash < 0 || slash == mimeType.length() - 1) return "bin";
        String extension = mimeType.substring(slash + 1);
        return "jpeg".equalsIgnoreCase(extension) ? "jpg" : extension;
    }

    private static String fileName(String path) {
        if (path == null || path.isEmpty()) return "ocr-image";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.isEmpty() ? "ocr-image" : name;
    }

    private static class ImageData {
        private final byte[] bytes;
        private final String fileName;

        private ImageData(byte[] bytes, String fileName) {
            this.bytes = bytes;
            this.fileName = fileName;
        }
    }
}
