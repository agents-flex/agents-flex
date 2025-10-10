/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.util;

import com.agentsflex.core.llm.client.HttpClient;

import java.io.File;
import java.net.URLConnection;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class ImageUtil {

    private static final HttpClient imageHttpClient = new HttpClient();

    // 手动维护的扩展名 -> MIME 类型映射（覆盖 JDK 未识别的格式）
    private static final Map<String, String> EXTENSION_TO_MIME = new ConcurrentHashMap<>();

    static {
        // 常见图片格式（包括现代格式）
        EXTENSION_TO_MIME.put("jpg", "image/jpeg");
        EXTENSION_TO_MIME.put("jpeg", "image/jpeg");
        EXTENSION_TO_MIME.put("png", "image/png");
        EXTENSION_TO_MIME.put("gif", "image/gif");
        EXTENSION_TO_MIME.put("bmp", "image/bmp");
        EXTENSION_TO_MIME.put("svg", "image/svg+xml");
        EXTENSION_TO_MIME.put("webp", "image/webp");
        EXTENSION_TO_MIME.put("avif", "image/avif");
        EXTENSION_TO_MIME.put("jxl", "image/jxl");      // JPEG XL
        EXTENSION_TO_MIME.put("tiff", "image/tiff");
        EXTENSION_TO_MIME.put("tif", "image/tiff");
        EXTENSION_TO_MIME.put("ico", "image/x-icon");
        // 可根据项目需要继续扩展
    }

    /**
     * 将图片 URL 转换为 Data URI 格式的字符串（例如：image/jpeg;base64,...）
     *
     * @throws IllegalArgumentException 如果 URL 无效或无法获取内容
     */
    public static String imageUrlToDataUri(String imageUrl) {
        Objects.requireNonNull(imageUrl, "Image URL must not be null");
        try {
            byte[] bytes = imageHttpClient.getBytes(imageUrl);
            String mimeType = guessMimeTypeFromName(imageUrl);
            return toDataUri(bytes, mimeType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert image URL to Data URI: " + imageUrl, e);
        }
    }

    /**
     * 将图片文件转换为 Data URI 格式的字符串
     *
     * @throws IllegalArgumentException 如果文件不存在或读取失败
     */
    public static String imageFileToDataUri(File imageFile) {
        Objects.requireNonNull(imageFile, "Image file must not be null");
        byte[] bytes = IOUtil.readBytes(imageFile);
        String mimeType = guessMimeTypeFromName(imageFile.getName());
        return toDataUri(bytes, mimeType);
    }


    /**
     * 根据文件名（或 URL）提取扩展名，并返回对应的 MIME 类型。
     * 优先使用内置映射，其次尝试 JDK 的 guessContentTypeFromName，最后 fallback 到 image/jpeg。
     */
    private static String guessMimeTypeFromName(String name) {
        if (name == null || name.isEmpty()) {
            return "image/jpeg";
        }

        // 提取扩展名（最后一个 '.' 之后的部分）
        int lastDotIndex = name.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == name.length() - 1) {
            // 无扩展名，尝试让 JDK 猜测（虽然大概率失败）
            String mime = URLConnection.guessContentTypeFromName(name);
            return mime != null ? mime : "image/jpeg";
        }

        String ext = name.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
        String mime = EXTENSION_TO_MIME.get(ext);
        if (mime != null) {
            return mime;
        }

        // JDK 可能认识一些格式（如 .png, .jpg），作为后备
        mime = URLConnection.guessContentTypeFromName(name);
        return mime != null ? mime : "image/jpeg";
    }

    // ========== Data URI 构造 ==========
    private static String toDataUri(byte[] data, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(data);
        return mimeType + ";base64," + base64;
    }
}
