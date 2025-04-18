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
package com.agentsflex.document.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

public class PdfBoxDocumentParser implements DocumentParser {

    /**
     * 返回整个文档的内容
     */
    @Override
    public Document parse(InputStream stream) {
        try (PDDocument pdfDocument = PDDocument.load(stream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdfDocument);
            return new Document(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 返回每页文档的内容
     */
    public List<Document> parseWithPage(InputStream inputStream) {
        try (PDDocument pdfDocument = PDDocument.load(inputStream)) {
            return getDocuments(pdfDocument);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Document> getDocuments(PDDocument pdDocument) throws IOException {
        List<Document> documents = new ArrayList<>();
        int pageCount = pdDocument.getNumberOfPages();
        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String content = stripper.getText(pdDocument);

            Document document = new Document();
            document.setContent(content);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("pageNumber", pageNumber);
            document.setMetadataMap(metadata);
            documents.add(document);
        }
        return documents;
    }

}
