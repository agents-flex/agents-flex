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
package com.agentsflex.core.document.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.id.DocumentIdGenerator;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleDocumentSplitter implements DocumentSplitter {
    private int chunkSize;
    private int overlapSize;

    public SimpleDocumentSplitter(int chunkSize) {
        this.chunkSize = chunkSize;
        if (this.chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0, chunkSize: " + this.chunkSize);
        }
    }

    public SimpleDocumentSplitter(int chunkSize, int overlapSize) {
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;

        if (this.chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0, chunkSize: " + this.chunkSize);
        }
        if (this.overlapSize < 0 || this.overlapSize >= this.chunkSize) {
            throw new IllegalArgumentException("overlapSize must be between 0 (inclusive) and chunkSize (exclusive), overlapSize: " + this.overlapSize + ", chunkSize: " + this.chunkSize);
        }
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0, chunkSize: " + chunkSize);
        }
        if (overlapSize >= chunkSize) {
            throw new IllegalArgumentException("overlapSize must be less than chunkSize, overlapSize: " + overlapSize + ", chunkSize: " + chunkSize);
        }
        this.chunkSize = chunkSize;
    }

    public int getOverlapSize() {
        return overlapSize;
    }

    public void setOverlapSize(int overlapSize) {
        if (overlapSize < 0 || overlapSize >= chunkSize) {
            throw new IllegalArgumentException("overlapSize must be between 0 (inclusive) and chunkSize (exclusive), overlapSize: " + overlapSize + ", chunkSize: " + chunkSize);
        }
        this.overlapSize = overlapSize;
    }

    @Override
    public List<Document> split(Document document, DocumentIdGenerator idGenerator) {
        if (document == null || StringUtil.noText(document.getContent())) {
            return Collections.emptyList();
        }

        String content = document.getContent();
        int index = 0, currentIndex = index;
        int maxIndex = content.length();

        List<Document> chunkDocuments = new ArrayList<>();
        while (currentIndex < maxIndex) {
            int endIndex = Math.min(currentIndex + chunkSize, maxIndex);
            String chunk = content.substring(currentIndex, endIndex).trim();
            currentIndex = currentIndex + chunkSize - overlapSize;

            if (chunk.isEmpty()) {
                continue;
            }

            Document chunkDocument = new Document();
            chunkDocument.setTitle(document.getTitle());
            chunkDocument.setContent(chunk);
            chunkDocument.putMetadata(document.getMetadataMap());

            chunkDocument.setId(idGenerator == null ? null : idGenerator.generateId(chunkDocument));
            chunkDocuments.add(chunkDocument);
        }

        return chunkDocuments;
    }
}
