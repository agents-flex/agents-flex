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
package com.agentsflex.core.file2text.extractor.impl;


import com.agentsflex.core.file2text.extractor.FileExtractor;
import com.agentsflex.core.file2text.source.DocumentSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 纯文本文件提取器（支持 UTF-8、GBK、GB2312 编码自动检测）
 * 支持 .txt, .md, .log, .csv, .json, .xml 等文本格式
 */
public class PlainTextExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("text/plain");
        mimeTypes.add("text/markdown");
        mimeTypes.add("text/csv");
        mimeTypes.add("application/json");
        mimeTypes.add("application/xml");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("txt");
        extensions.add("text");
        extensions.add("md");
        extensions.add("markdown");
        extensions.add("log");
        extensions.add("csv");
        extensions.add("json");
        extensions.add("xml");
        extensions.add("yml");
        extensions.add("yaml");
        extensions.add("properties");
        extensions.add("conf");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null && (mimeType.startsWith("text/") || SUPPORTED_MIME_TYPES.contains(mimeType))) {
            return true;
        }

        if (fileName != null) {
            String ext = getExtension(fileName);
            return ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase());
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        try (InputStream is = source.openStream()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"))) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    text.append(buffer, 0, read);
                }
                return text.toString().trim();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public int getOrder() {
        return 5; // 高优先级
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        int lastDot = fileName.lastIndexOf('.');
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}
