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
package com.agentsflex.core.store;

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;


/**
 * 向量存储使用的基础数据对象。
 *
 * <p>保存浮点向量、检索相似度分值以及继承自 {@link Metadata} 的业务元数据。</p>
 */
public class VectorData extends Metadata {

    /** 向量内容；未生成或查询未要求返回向量时可以为空。 */
    protected float[] vector;

    /**
     * 检索相似度分值，通常为 0 到 1 且数值越大越相似；具体范围以存储实现为准。
     */
    protected Float score;

    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

    /**
     * 将向量转换为 {@link Float} 列表。
     *
     * @return 新建的列表；向量为空时返回 {@code null}
     */
    public List<Float> getVectorAsList() {
        if (vector == null) {
            return null;
        }
        List<Float> result = new ArrayList<>(vector.length);
        for (float v : vector) {
            result.add(v);
        }
        return result;
    }

    /**
     * 将向量转换为 {@link Double} 列表，便于适配只接受双精度数值的客户端。
     *
     * @return 新建的列表；向量为空时返回 {@code null}
     */
    public List<Double> getVectorAsDoubleList() {
        if (vector == null) {
            return null;
        }
        List<Double> result = new ArrayList<>(vector.length);
        for (float v : vector) {
            result.add((double) v);
        }
        return result;
    }

    /**
     * 从任意数字集合设置向量，每个元素通过 {@link Number#floatValue()} 转换。
     * {@code null} 或空集合会清空当前向量。
     */
    public void setVectorByNumbers(Collection<? extends Number> vector) {
        if (vector == null || vector.isEmpty()) {
            this.vector = null;
        } else {
            this.vector = new float[vector.size()];
            int index = 0;
            for (Number num : vector) {
                this.vector[index++] = num.floatValue();
            }
        }
    }

    public Float getScore() {
        return score;
    }

    public void setScore(Float score) {
        this.score = score;
    }

    @Override
    public String toString() {
        return "VectorData{" +
            "vector=" + Arrays.toString(vector) +
            ", score=" + score +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
