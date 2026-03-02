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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * PDF 文本提取器
 * 支持标准 PDF（非扫描件）
 */
public class PdfTextExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/pdf");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("pdf");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType)) {
            return true;
        }

        if (fileName != null) {
            String ext = getExtension(fileName);
            return "pdf".equalsIgnoreCase(ext);
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        try (InputStream is = source.openStream();
             PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc).trim();
        } catch (Exception e) {
            throw new IOException("Failed to extract PDF text: " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        int lastDot = fileName.lastIndexOf('.');
        return fileName.substring(lastDot + 1);
    }
}
