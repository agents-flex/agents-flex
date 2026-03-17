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


public class VectorData extends Metadata {

    protected float[] vector;

    /**
     * 0 ~ 1, 数值越大，相似度越高
     */
    protected Float score;

    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

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

    public double[] getVectorAsDoubleArray() {
        if (vector == null) {
            return null;
        }
        double[] result = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i];
        }
        return result;
    }

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
