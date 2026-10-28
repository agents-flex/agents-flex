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
package com.agentsflex.core.model.ocr;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 结构化 OCR 文档公共模型的行为测试。
 */
public class OcrStructuredDocumentTest {
    /**
     * 验证响应可携带页面结构、坐标体系和块级结果。
     */
    @Test
    public void shouldAttachStructuredDocumentToResponse() {
        OcrBlock block = new OcrBlock();
        block.setIndex(3);
        block.setType(OcrBlockType.TITLE);
        block.setProviderType("heading");
        block.setBoundingBox(new OcrBoundingBox(10, 20, 110, 60));
        block.setCaptionBlockIndexes(Arrays.asList(4, 5));

        OcrPage page = new OcrPage();
        page.setPageNumber(0);
        page.setSize(new OcrPageSize(1190, 1684, OcrCoordinateUnit.PIXEL));
        page.setCoordinateOrigin(OcrCoordinateOrigin.TOP_LEFT);
        page.addBlock(block);

        OcrDocument document = new OcrDocument();
        document.setFileName("document.pdf");
        document.addPage(page);
        OcrResponse response = new OcrResponse();
        response.setDocument(document);

        assertNotNull(response.getDocument());
        assertEquals(1, response.getDocument().getPages().size());
        assertEquals(100D, block.getBoundingBox().getWidth(), 0D);
        assertEquals(40D, block.getBoundingBox().getHeight(), 0D);
    }

    /**
     * 集合 setter 应防御性复制，getter 不允许调用方修改内部状态。
     */
    @Test(expected = UnsupportedOperationException.class)
    public void shouldExposeImmutablePageCollections() {
        List<OcrPage> source = new ArrayList<>();
        source.add(new OcrPage());
        OcrDocument document = new OcrDocument();
        document.setPages(source);
        source.clear();

        assertEquals(1, document.getPages().size());
        document.getPages().add(new OcrPage());
    }
}
