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
package com.agentsflex.core.store;

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;


public class VectorData extends Metadata {

    private float[] vector;

    /**
     * 0 ~ 1, 数值越大，相似度越高
     */
    private Double score;

    public float[] getVector() {
        return vector;
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


    public void setVector(float[] vector) {
        this.vector = vector;
    }

    public void setVector(Float[] vector) {
        this.vector = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            this.vector[i] = vector[i];
        }
    }

    public void setVector(double[] vector) {
        this.vector = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            this.vector[i] = (float) vector[i];
        }
    }

    public void setVector(Double[] vector) {
        this.vector = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            this.vector[i] = vector[i].floatValue();
        }
    }

    public void setVector(Collection<? extends Number> vector) {
        this.vector = new float[vector.size()];
        int index = 0;
        for (Number num : vector) {
            this.vector[index++] = num.floatValue();
        }
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
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
