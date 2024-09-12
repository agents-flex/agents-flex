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
package com.agentsflex.image.siliconflow;


import java.io.Serializable;

public class SiliconflowImageModelConfig implements Serializable {
    private String endpoint = "https://api.siliconflow.cn";
    private String model = SiliconflowImageModels.flux_1_schnell;
    private String apiKey;
    private Integer numInferenceSteps = 20;
    private Integer guidanceScale = 7;
    private String imageSize = "1024x1024";


    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getNumInferenceSteps() {
        return numInferenceSteps;
    }

    public void setNumInferenceSteps(Integer numInferenceSteps) {
        this.numInferenceSteps = numInferenceSteps;
    }

    public Integer getGuidanceScale() {
        return guidanceScale;
    }

    public void setGuidanceScale(Integer guidanceScale) {
        this.guidanceScale = guidanceScale;
    }

    public String getImageSize() {
        return imageSize;
    }

    public void setImageSize(String imageSize) {
        this.imageSize = imageSize;
    }
}
