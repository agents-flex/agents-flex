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
package com.agentsflex.core.file2text.util;


import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 工业级编码检测工具类（支持流式读取大文件，自动识别编码并返回 Reader）
 *
 * <p>特性：
 * <ul>
 *     <li>支持 PushbackInputStream，避免依赖 mark/reset</li>
 *     <li>支持 UTF-8, UTF-16LE/BE, UTF-32LE/BE BOM 检测</li>
 *     <li>ICU4J 异常降级处理，默认回退 UTF-8</li>
 *     <li>支持自定义置信度阈值</li>
 *     <li>编码名称标准化</li>
 *     <li>返回的 Reader 关闭时会级联关闭底层 InputStream</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * try (Reader reader = EncodingDetectUtil.getAutoDetectReader(inputStream)) {
 *     BufferedReader br = new BufferedReader(reader);
 *     String line;
 *     while ((line = br.readLine()) != null) {
 *         System.out.println(line);
 *     }
 * }
 * }</pre>
 *
 * <p>调用方必须关闭返回的 Reader，以释放底层 InputStream。
 */
public class EncodingDetectUtil {

    private static final int DEFAULT_DETECT_BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final int DEFAULT_CONFIDENCE_THRESHOLD = 60;

    private EncodingDetectUtil() {
        // 工具类禁止实例化
    }

    // ==========================
    // 对外 API
    // ==========================


    /**
     * 自动读取 InputStream 为文本
     *
     * @param inputStream 待读取流
     * @return 完整文本
     * @throws IOException IO 异常
     */
    public static String readToString(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(getAutoDetectReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /**
     * 自动识别 InputStream 编码并返回 Reader（默认置信度阈值 60）
     *
     * @param inputStream 待检测流
     * @return 自动识别编码的 Reader
     * @throws IOException IO 异常
     */
    public static Reader getAutoDetectReader(InputStream inputStream) throws IOException {
        return getAutoDetectReader(inputStream, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    /**
     * 自动识别 InputStream 编码并返回 Reader，可自定义置信度阈值
     *
     * @param inputStream         待检测流
     * @param confidenceThreshold ICU4J 检测置信度阈值（0-100）
     * @return 自动识别编码的 Reader
     * @throws IOException IO 异常
     */
    public static Reader getAutoDetectReader(InputStream inputStream, int confidenceThreshold) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream cannot be null");
        }
        if (confidenceThreshold < 0 || confidenceThreshold > 100) {
            confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
        }

        PushbackInputStream pbis = new PushbackInputStream(inputStream, DEFAULT_DETECT_BUFFER_SIZE);
        byte[] detectBytes = new byte[DEFAULT_DETECT_BUFFER_SIZE];
        int read = pbis.read(detectBytes);

        String charsetName;
        if (read <= 0) {
            charsetName = StandardCharsets.UTF_8.name();
        } else {
            // 1. 检测 BOM
            String bomCharset = detectBom(detectBytes, read);
            if (bomCharset != null) {
                charsetName = bomCharset;
            } else {
                // 2. ICU4J 自动检测
                charsetName = detectCharsetICU(detectBytes, read, confidenceThreshold);
            }
        }

        // 推回已读取字节，保证流式读取完整数据
        if (read > 0) {
            pbis.unread(detectBytes, 0, read);
        }

        return new InputStreamReader(pbis, Charset.forName(charsetName));
    }

    /**
     * 自动检测编码（只返回编码名称）
     *
     * @param inputStream 待检测流
     * @return 编码名称（标准 JVM 名称）
     * @throws IOException IO 异常
     */
    public static String detectCharset(InputStream inputStream) throws IOException {
        try (Reader r = getAutoDetectReader(inputStream)) {
            return ((InputStreamReader) r).getEncoding();
        }
    }

    // ==========================
    // 内部方法
    // ==========================

    /**
     * BOM 检测（含 UTF-8/16/32）
     */
    private static String detectBom(byte[] bytes, int length) {
        if (length >= 3 && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8.name();
        }
        if (length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }
        if (length >= 4 && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFE
            && (bytes[2] & 0xFF) == 0x00
            && (bytes[3] & 0xFF) == 0x00) {
            return "UTF-32LE";
        }
        if (length >= 4 && (bytes[0] & 0xFF) == 0x00
            && (bytes[1] & 0xFF) == 0x00
            && (bytes[2] & 0xFF) == 0xFE
            && (bytes[3] & 0xFF) == 0xFF) {
            return "UTF-32BE";
        }
        return null;
    }

    /**
     * 使用 ICU4J 检测编码，支持置信度阈值
     */
    private static String detectCharsetICU(byte[] bytes, int length, int confidenceThreshold) {
        try {
            CharsetDetector detector = new CharsetDetector();
            detector.enableInputFilter(true); // 过滤 HTML/XML 标签提高准确性

            if (length < bytes.length) {
                // 只取前 length 字节
                byte[] sample = new byte[length];
                System.arraycopy(bytes, 0, sample, 0, length);
                detector.setText(sample);
            } else {
                detector.setText(bytes);
            }

            CharsetMatch match = detector.detect();

            if (match != null && match.getConfidence() >= confidenceThreshold) {
                String name = match.getName();
                // GBK 升级为 GB18030
                if ("GBK".equalsIgnoreCase(name)) {
                    return "GB18030";
                }
                return Charset.forName(name).name(); // 标准化
            }
        } catch (Exception e) {
            // ICU4J 异常降级到 UTF-8
        }
        return StandardCharsets.UTF_8.name();
    }
}
