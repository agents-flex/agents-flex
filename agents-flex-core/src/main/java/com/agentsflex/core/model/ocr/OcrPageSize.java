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

/**
 * OCR 页面的宽、高及其坐标单位。
 */
public class OcrPageSize extends Metadata {
    private double width;
    private double height;
    private OcrCoordinateUnit unit = OcrCoordinateUnit.UNKNOWN;

    public OcrPageSize() {
    }

    public OcrPageSize(double width, double height, OcrCoordinateUnit unit) {
        this.width = width;
        this.height = height;
        setUnit(unit);
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public OcrCoordinateUnit getUnit() {
        return unit;
    }

    public void setUnit(OcrCoordinateUnit unit) {
        this.unit = unit == null ? OcrCoordinateUnit.UNKNOWN : unit;
    }
}
