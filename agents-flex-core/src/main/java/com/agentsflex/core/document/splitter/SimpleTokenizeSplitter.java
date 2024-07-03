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
package com.agentsflex.core.document.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.id.DocumentIdGenerator;
import com.agentsflex.core.util.StringUtil;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleTokenizeSplitter implements DocumentSplitter {
    private EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
    private EncodingType encodingType = EncodingType.CL100K_BASE;
    private int chunkSize;
    private int overlapSize;

    public SimpleTokenizeSplitter(int chunkSize) {
        this.chunkSize = chunkSize;
        if (this.chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0, chunkSize: " + this.chunkSize);
        }
    }

    public SimpleTokenizeSplitter(int chunkSize, int overlapSize) {
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;

        if (this.chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be greater than 0, chunkSize: " + this.chunkSize);
        }
        if (this.overlapSize >= this.chunkSize) {
            throw new IllegalArgumentException("overlapSize must be less than chunkSize, overlapSize: " + this.overlapSize + ", chunkSize: " + this.chunkSize);
        }
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getOverlapSize() {
        return overlapSize;
    }

    public void setOverlapSize(int overlapSize) {
        this.overlapSize = overlapSize;
    }

    public EncodingRegistry getRegistry() {
        return registry;
    }

    public void setRegistry(EncodingRegistry registry) {
        this.registry = registry;
    }

    public EncodingType getEncodingType() {
        return encodingType;
    }

    public void setEncodingType(EncodingType encodingType) {
        this.encodingType = encodingType;
    }

    @Override
    public List<Document> split(Document document, DocumentIdGenerator idGenerator) {
        if (document == null || StringUtil.noText(document.getContent())) {
            return Collections.emptyList();
        }

        String content = document.getContent();
        Encoding encoding = this.registry.getEncoding(this.encodingType);

        List<Integer> tokens = encoding.encode(content).boxed();


        int index = 0, currentIndex = index;
        int maxIndex = tokens.size();

        List<Document> chunks = new ArrayList<>();
        while (currentIndex < maxIndex) {
            int endIndex = Math.min(currentIndex + chunkSize, maxIndex);
            List<Integer> chunkTokens = tokens.subList(currentIndex, endIndex);
            currentIndex = currentIndex + chunkSize - overlapSize;

            IntArrayList intArrayList = new IntArrayList();
            for (Integer chunkToken : chunkTokens) {
                intArrayList.add(chunkToken);
            }
            String chunkText = encoding.decode(intArrayList).trim();

            if (chunkText.isEmpty()) {
                continue;
            }

            Document newDocument = new Document();
            newDocument.addMetadata(document.getMetadataMap());
            newDocument.setContent(chunkText);

            //we should invoke setId after setContent
            newDocument.setId(idGenerator == null ? null : idGenerator.generateId(newDocument));
            chunks.add(newDocument);
        }

        return chunks;
    }
}
