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
package com.agentsflex.core.document;

import com.agentsflex.core.document.id.DocumentIdGenerator;

import java.util.Collections;
import java.util.List;

import static java.util.stream.Collectors.toList;

public interface DocumentSplitter {

    List<Document> split(Document text, DocumentIdGenerator idGenerator);

    default List<Document> split(Document text) {
        return split(text, null);
    }

    default List<Document> splitAll(List<Document> documents, DocumentIdGenerator idGenerator) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        return documents.stream()
            .flatMap(document -> split(document, idGenerator).stream())
            .collect(toList());
    }
}
