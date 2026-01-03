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
package com.agentsflex.image.siliconflow.test;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.siliconflow.SiliconImageModel;
import com.agentsflex.image.siliconflow.SiliconflowImageModelConfig;
import com.agentsflex.image.siliconflow.SiliconflowImageModels;
import org.junit.Test;

import java.io.File;

public class SiliconflowImageModelTest {

    @Test
    public void testGenImage(){
        SiliconflowImageModelConfig config = new SiliconflowImageModelConfig();
        config.setModel(SiliconflowImageModels.Stable_Diffusion_XL);
        config.setApiKey("sk-****");

        SiliconImageModel imageModel = new SiliconImageModel(config);

        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A cute little tiger standing in the high-speed train");
        request.setSize(1024,1024);
        request.setN(4);
        ImageResponse generate = imageModel.generate(request);
        if (generate != null && generate.getImages() != null){
            int index = 0;
            for (Image image : generate.getImages()) {
                image.writeToFile(new File("/Users/michael/Desktop/test/image"+(index++)+".jpg"));
            }
        }

        System.out.println(generate);
    }

}
