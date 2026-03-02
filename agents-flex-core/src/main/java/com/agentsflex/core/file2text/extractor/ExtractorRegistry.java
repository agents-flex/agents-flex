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
package com.agentsflex.core.file2text.extractor;


import com.agentsflex.core.file2text.extractor.impl.*;
import com.agentsflex.core.file2text.source.DocumentSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Extractor 注册中心
 */
public class ExtractorRegistry {

    private final List<FileExtractor> extractors = new ArrayList<>();

    public ExtractorRegistry() {
        register(new PdfTextExtractor());
        register(new DocxExtractor());
        register(new DocExtractor());
        register(new PptxExtractor());
        register(new HtmlExtractor());
        register(new PlainTextExtractor());
    }

    /**
     * 注册一个 Extractor
     */
    public synchronized void register(FileExtractor extractor) {
        Objects.requireNonNull(extractor, "Extractor cannot be null");
        extractors.add(extractor);
    }

    /**
     * 批量注册
     */
    public void registerAll(List<FileExtractor> extractors) {
        extractors.forEach(this::register);
    }


    public List<FileExtractor> findExtractors(DocumentSource source) {
        return extractors.stream()
            .filter(extractor -> extractor.supports(source))
            .sorted(FileExtractor.ORDER_COMPARATOR)
            .collect(Collectors.toList());
    }


}
