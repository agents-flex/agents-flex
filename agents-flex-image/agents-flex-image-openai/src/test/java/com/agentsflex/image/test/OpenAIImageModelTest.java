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
package com.agentsflex.image.test;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.openai.OpenAIImageModel;
import com.agentsflex.image.openai.OpenAIImageModelConfig;
import org.junit.Test;

public class OpenAIImageModelTest {

    @Test
    public void testGenImage(){
        OpenAIImageModelConfig config = new OpenAIImageModelConfig();
        config.setApiKey("sk-5gqOclb****");

        OpenAIImageModel imageModel = new OpenAIImageModel(config);

        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A cute little tiger standing in the high-speed train");
        ImageResponse generate = imageModel.generate(request);
        System.out.println(generate);
    }
}
