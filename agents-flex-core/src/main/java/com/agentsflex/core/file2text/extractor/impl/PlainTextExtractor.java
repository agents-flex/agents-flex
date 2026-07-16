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
package com.agentsflex.core.file2text.extractor.impl;


import com.agentsflex.core.file2text.extractor.FileExtractor;
import com.agentsflex.core.file2text.source.DocumentSource;
import com.agentsflex.core.file2text.util.EncodingDetectUtil;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 纯文本文件提取器（支持 UTF-8、GBK、GB2312 编码自动检测）
 * 支持基础文本、TSV、常见代码和配置文件
 */
public class PlainTextExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("text/plain");
        mimeTypes.add("text/markdown");
        mimeTypes.add("text/csv");
        mimeTypes.add("text/tab-separated-values");
        mimeTypes.add("application/json");
        mimeTypes.add("application/xml");
        mimeTypes.add("application/javascript");
        mimeTypes.add("application/sql");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        Collections.addAll(extensions,
            "txt", "text", "md", "markdown", "log", "csv", "tsv",
            "json", "xml", "yml", "yaml", "properties", "conf", "cfg", "config",
            "ini", "toml", "env", "editorconfig", "gitignore", "gitattributes",
            "java", "kt", "kts", "groovy", "scala", "py", "rb", "php",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
            "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "swift", "dart", "lua", "r",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
            "sql", "css", "scss", "sass", "less", "gradle");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();
        String normalizedMimeType = mimeType != null
            ? mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)
            : null;

        if (normalizedMimeType != null
            && (normalizedMimeType.startsWith("text/") || SUPPORTED_MIME_TYPES.contains(normalizedMimeType))) {
            return true;
        }

        if (fileName != null) {
            String ext = getExtension(fileName);
            String normalizedFileName = fileName.trim().toLowerCase(Locale.ROOT);
            if ("dockerfile".equals(normalizedFileName)
                || "makefile".equals(normalizedFileName)
                || "jenkinsfile".equals(normalizedFileName)) {
                return true;
            }
            return ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) {
        try (InputStream is = source.openStream()) {
            return EncodingDetectUtil.readToString(is);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public int getOrder() {
        return 5; // 高优先级
    }

}
