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
package com.agentsflex.core.message;

import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.functions.JavaNativeFunctionBuilder;
import com.agentsflex.core.util.ImageUtil;

import java.io.File;
import java.util.*;

public class UserMessage extends AbstractTextMessage {

    private List<String> audioUrls;
    private List<String> videoUrls;
    private List<String> imageUrls;
    private List<Function> functions;
    private String toolChoice;

    public UserMessage() {
    }

    public UserMessage(String content) {
        setContent(content);
    }

    public void addFunction(Function function) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.add(function);
    }

    public void addFunctions(Collection<? extends Function> functions) {
        if (this.functions == null) {
            this.functions = new java.util.ArrayList<>();
        }
        if (functions != null) {
            this.functions.addAll(functions);
        }
    }

    public void addFunctionsFromClass(Class<?> funcClass, String... methodNames) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.addAll(JavaNativeFunctionBuilder.fromClass(funcClass, methodNames));
    }

    public void addFunctionsFromObject(Object funcObject, String... methodNames) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.addAll(JavaNativeFunctionBuilder.fromObject(funcObject, methodNames));
    }

    public List<Function> getFunctions() {
        return functions;
    }

    public Map<String, Function> getFunctionMap() {
        if (functions == null) {
            return Collections.emptyMap();
        }
        Map<String, Function> map = new HashMap<>(functions.size());
        for (Function function : functions) {
            map.put(function.getName(), function);
        }
        return map;
    }

    public void setFunctions(List<? extends Function> functions) {
        if (functions == null) {
            this.functions = null;
        } else {
            this.functions = new ArrayList<>(functions);
        }
    }

    public String getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
    }


    /// /// Audio
    public List<String> getAudioUrls() {
        return audioUrls;
    }

    public void setAudioUrls(List<String> audioUrls) {
        this.audioUrls = audioUrls;
    }

    public void addAudioUrl(String audioUrl) {
        if (audioUrls == null) {
            audioUrls = new ArrayList<>(1);
        }
        audioUrls.add(audioUrl);
    }


    /// ///  Video
    public List<String> getVideoUrls() {
        return videoUrls;
    }

    public void setVideoUrls(List<String> videoUrls) {
        this.videoUrls = videoUrls;
    }

    public void addVideoUrl(String videoUrl) {
        if (videoUrls == null) {
            videoUrls = new ArrayList<>(1);
        }
        videoUrls.add(videoUrl);
    }


    /// /// Images
    public List<String> getImageUrls() {
        return imageUrls;
    }

    public List<String> getImageUrlsForChat(ChatConfig config) {
        if (this.imageUrls == null) {
            return null;
        }
        List<String> result = new ArrayList<>(this.imageUrls.size());
        for (String url : imageUrls) {
            if (config != null && config.isSupportImageBase64Only()
                && url.toLowerCase().startsWith("http")) {
                url = ImageUtil.imageUrlToDataUri(url);
            }
            result.add(url);
        }
        return result;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public void addImageUrl(String imageUrl) {
        if (this.imageUrls == null) {
            this.imageUrls = new ArrayList<>(1);
        }
        this.imageUrls.add(imageUrl);
    }

    public void addImageFile(File imageFile) {
        addImageUrl(ImageUtil.imageFileToDataUri(imageFile));
    }

    public void addImageBytes(byte[] imageBytes, String mimeType) {
        addImageUrl(ImageUtil.imageBytesToDataUri(imageBytes, mimeType));
    }


    @Override
    public String toString() {
        return "UserMessage{" +
            "audioUrls=" + audioUrls +
            ", videoUrls=" + videoUrls +
            ", imageUrls=" + imageUrls +
            ", functions=" + functions +
            ", toolChoice='" + toolChoice + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
