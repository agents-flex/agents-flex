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
package com.agentsflex.core.file2text;


import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import com.agentsflex.core.file2text.source.ByteStreamDocumentSource;
import com.agentsflex.core.file2text.source.FileDocumentSource;
import com.agentsflex.core.file2text.source.HttpDocumentSource;

import java.io.File;
import java.io.InputStream;

public class File2TextUtil {
    private static File2TextService file2TextService = new File2TextService();

    public static void setFile2TextService(File2TextService file2TextService) {
        if (file2TextService == null) {
            throw new IllegalArgumentException("File2TextService cannot be null");
        }
        File2TextUtil.file2TextService = file2TextService;
    }

    public static String readFromHttpUrl(String httpUrl) {
        return file2TextService.extractTextFromSource(new HttpDocumentSource(httpUrl));
    }

    public static String readFromHttpUrl(String httpUrl, String fileName) {
        return file2TextService.extractTextFromSource(new HttpDocumentSource(httpUrl, fileName));
    }

    public static String readFromHttpUrl(String httpUrl, String fileName, String mimeType) {
        return file2TextService.extractTextFromSource(new HttpDocumentSource(httpUrl, fileName, mimeType));
    }

    public static String readFromFile(File file) {
        return file2TextService.extractTextFromSource(new FileDocumentSource(file));
    }

    public static String readFromStream(InputStream is, String fileName, String mimeType) {
        return file2TextService.extractTextFromSource(new ByteStreamDocumentSource(is, fileName, mimeType));
    }

    public static String readFromBytes(byte[] bytes, String fileName, String mimeType) {
        return file2TextService.extractTextFromSource(new ByteArrayDocumentSource(bytes, fileName, mimeType));
    }


}
