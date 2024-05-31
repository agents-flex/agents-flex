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
package com.agentsflex.util;

import java.util.ArrayList;
import java.util.List;

public class VectorUtil {

    public static float[] toFloatArray(double[] vector) {
        if (vector == null) {
            return null;
        }
        float[] output = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            output[i] = (float) vector[i];
        }
        return output;
    }


    public static List<Float> toFloatList(double[] vector) {
        if (vector == null) {
            return null;
        }
        List<Float> output = new ArrayList<>(vector.length);
        for (double v : vector) {
            output.add((float) v);
        }
        return output;
    }

    public static double[] convertToVector(List<Float> floats) {
        if (floats == null) {
            return null;
        }
        double[] output = new double[floats.size()];
        int index = 0;
        for (Float aFloat : floats) {
            output[index++] = aFloat;
        }
        return output;
    }
}
