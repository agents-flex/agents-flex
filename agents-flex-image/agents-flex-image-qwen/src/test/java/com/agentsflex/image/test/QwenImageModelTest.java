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
package com.agentsflex.image.test;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageModel;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.qwen.QwenImageModel;
import com.agentsflex.image.qwen.QwenImageModelConfig;
import org.junit.Test;

public class QwenImageModelTest {

    @Test
    public void testGenImage() throws InterruptedException {
        Thread thread = new Thread(() -> {
            QwenImageModelConfig config = new QwenImageModelConfig();
            config.setApiKey("******************");
            ImageModel imageModel = new QwenImageModel(config);
            GenerateImageRequest request = new GenerateImageRequest();
            request.setPrompt("雨中, 竹林,  小路");
            request.setModel("flux-schnell");
            ImageResponse generate = imageModel.generate(request);
            System.out.println(generate);
        });

//        Thread thread2 = new Thread(() -> {
//            QwenImageModelConfig config = new QwenImageModelConfig();
//            config.setApiKey("******************");
//            ImageModel imageModel = new QwenImageModel(config);
//            GenerateImageRequest request = new GenerateImageRequest();
//            request.setPrompt("雨中, 竹林,  小路");
//            request.setModel("flux-schnell");
//            ImageResponse generate = imageModel.generate(request);
//        });
        thread.start();
//        thread2.start();
        thread.join();
//        thread2.join();
    }
}
