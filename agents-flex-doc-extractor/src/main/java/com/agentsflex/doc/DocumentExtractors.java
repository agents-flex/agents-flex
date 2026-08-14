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
package com.agentsflex.doc;


import com.agentsflex.core.document.ExtractedImageHandler;
import com.agentsflex.doc.source.ByteArrayDocumentSource;
import com.agentsflex.doc.source.ByteStreamDocumentSource;
import com.agentsflex.doc.source.FileDocumentSource;
import com.agentsflex.doc.source.HttpDocumentSource;

import java.io.File;
import java.io.InputStream;

public class DocumentExtractors {
    private static volatile DocumentExtractionService defaultService = new DocumentExtractionService();

    private DocumentExtractors() {
    }

    public static void setDefault(DocumentExtractionService service) {
        if (service == null) {
            throw new IllegalArgumentException("DocumentExtractionService cannot be null");
        }
        DocumentExtractors.defaultService = service;
    }

    /**
     * 设置 extractedImageHandler ，全局只需要配置 1 次。
     * @param extractedImageHandler
     */
    public static void setExtractedImageHandler(ExtractedImageHandler extractedImageHandler) {
        defaultService.setExtractedImageHandler(extractedImageHandler);
    }

    public static String extractFromUrl(String httpUrl) {
        return defaultService.extract(new HttpDocumentSource(httpUrl));
    }

    public static String extractFromUrl(String httpUrl, String fileName) {
        return defaultService.extract(new HttpDocumentSource(httpUrl, fileName));
    }

    public static String extractFromUrl(String httpUrl, String fileName, String mimeType) {
        return defaultService.extract(new HttpDocumentSource(httpUrl, fileName, mimeType));
    }

    public static String extract(File file) {
        return defaultService.extract(new FileDocumentSource(file));
    }

    public static String extract(InputStream inputStream, String fileName, String mimeType) {
        return defaultService.extract(new ByteStreamDocumentSource(inputStream, fileName, mimeType));
    }

    public static String extract(byte[] bytes, String fileName, String mimeType) {
        return defaultService.extract(new ByteArrayDocumentSource(bytes, fileName, mimeType));
    }


}
