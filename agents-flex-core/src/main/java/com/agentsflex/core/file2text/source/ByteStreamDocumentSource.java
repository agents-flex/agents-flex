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

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class ByteStreamDocumentSource implements DocumentSource {
    private final byte[] data;
    private final String fileName;
    private final String mimeType;

    public ByteStreamDocumentSource(InputStream inputStream, String fileName) {
        this(inputStream, fileName, null);
    }

    public ByteStreamDocumentSource(InputStream inputStream, String fileName, String mimeType) {
        this.data = IOUtils.toByteArray(inputStream, Integer.MAX_VALUE);
        this.fileName = fileName;
        this.mimeType = mimeType;
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    @Override
    public InputStream openStream() {
        return new ByteArrayInputStream(data);
    }

}
