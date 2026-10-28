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

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR 结果中独立于任务状态的结构化文档内容。
 */
public class OcrDocument extends Metadata {
    private String fileName;
    private List<OcrPage> pages;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public List<OcrPage> getPages() {
        return pages == null ? Collections.emptyList() : Collections.unmodifiableList(pages);
    }

    public void setPages(List<OcrPage> pages) {
        this.pages = pages == null ? null : new ArrayList<>(pages);
    }

    public void addPage(OcrPage page) {
        if (page == null) return;
        if (pages == null) pages = new ArrayList<>();
        pages.add(page);
    }
}
