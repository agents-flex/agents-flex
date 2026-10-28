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
 * OCR 文档中的单页结构化结果。
 */
public class OcrPage extends Metadata {
    /**
     * 供应商返回的页码；适配器应保留供应商的起始编号约定。
     */
    private int pageNumber;
    /**
     * 页面尺寸和坐标单位。
     */
    private OcrPageSize size;
    /**
     * 页面坐标系原点。
     */
    private OcrCoordinateOrigin coordinateOrigin = OcrCoordinateOrigin.UNKNOWN;
    /**
     * 相对原始方向顺时针旋转的角度；供应商未提供时为空。
     */
    private Integer rotationDegrees;
    /**
     * 当前页按供应商阅读顺序排列的内容块。
     */
    private List<OcrBlock> blocks;
    /**
     * 当前页开头内容是否由供应商与上一页末尾内容合并。
     */
    private boolean mergedFromPreviousPage;

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public OcrPageSize getSize() {
        return size;
    }

    public void setSize(OcrPageSize size) {
        this.size = size;
    }

    public OcrCoordinateOrigin getCoordinateOrigin() {
        return coordinateOrigin;
    }

    public void setCoordinateOrigin(OcrCoordinateOrigin coordinateOrigin) {
        this.coordinateOrigin = coordinateOrigin == null ? OcrCoordinateOrigin.UNKNOWN : coordinateOrigin;
    }

    public Integer getRotationDegrees() {
        return rotationDegrees;
    }

    public void setRotationDegrees(Integer rotationDegrees) {
        this.rotationDegrees = rotationDegrees;
    }

    public List<OcrBlock> getBlocks() {
        return blocks == null ? Collections.emptyList() : Collections.unmodifiableList(blocks);
    }

    public void setBlocks(List<OcrBlock> blocks) {
        this.blocks = blocks == null ? null : new ArrayList<>(blocks);
    }

    public void addBlock(OcrBlock block) {
        if (block == null) return;
        if (blocks == null) blocks = new ArrayList<>();
        blocks.add(block);
    }

    public boolean isMergedFromPreviousPage() {
        return mergedFromPreviousPage;
    }

    public void setMergedFromPreviousPage(boolean mergedFromPreviousPage) {
        this.mergedFromPreviousPage = mergedFromPreviousPage;
    }
}
