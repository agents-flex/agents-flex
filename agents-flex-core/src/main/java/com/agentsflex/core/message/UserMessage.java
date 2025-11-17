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

import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.functions.JavaNativeFunctionBuilder;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.ImageUtil;
import com.agentsflex.core.util.Maps;

import java.io.File;
import java.util.*;

public class UserMessage extends AbstractTextMessage {

    private List<String> audioUrls;
    private List<String> videoUrls;
    private List<String> imageUrls;
    private List<String> imageBase64s;

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
        this.functions.addAll(functions);
    }

    public void addFunctions(Class<?> funcClass, String... methodNames) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.addAll(JavaNativeFunctionBuilder.from(funcClass, methodNames));
    }

    public void addFunctions(Object funcObject, String... methodNames) {
        if (this.functions == null)
            this.functions = new java.util.ArrayList<>();
        this.functions.addAll(JavaNativeFunctionBuilder.from(funcObject, methodNames));
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
        addImageBase64(ImageUtil.imageFileToDataUri(imageFile));
    }

    public List<String> getImageBase64s() {
        return imageBase64s;
    }

    public void setImageBase64s(List<String> imageBase64s) {
        this.imageBase64s = imageBase64s;
    }

    public void addImageBase64(String imageBase64) {
        if (this.imageBase64s == null) {
            this.imageBase64s = new ArrayList<>(1);
        }
        this.imageBase64s.add(imageBase64);
    }

    public List<String> buildAllToBase64s() {
        List<String> allBase64s = new ArrayList<>();
        if (imageUrls != null) {
            for (String imageUrl : imageUrls) {
                allBase64s.add(ImageUtil.imageUrlToDataUri(imageUrl));
            }
        }
        if (imageBase64s != null) {
            allBase64s.addAll(imageBase64s);
        }
        return allBase64s;
    }

    @Override
    public Object getMessageContent() {
        if (CollectionUtil.hasItems(audioUrls) || CollectionUtil.hasItems(videoUrls)
            || CollectionUtil.hasItems(imageUrls) || CollectionUtil.hasItems(imageBase64s)) {
            List<Map<String, Object>> messageContent = new ArrayList<>();
            messageContent.add(Maps.of("type", "text").set("text", getContent()));
            messageContent.addAll(buildAudioContent());
            messageContent.addAll(buildVideoContent());
            messageContent.addAll(buildImageContent());
            return messageContent;
        } else {
            return super.getMessageContent();
        }
    }


    public List<Map<String, Object>> buildAudioContent() {
        List<Map<String, Object>> result = new ArrayList<>(1);
        if (audioUrls != null) {
            for (String url : audioUrls) {
                result.add(Maps.of("type", "audio_url").set("audio_url", Maps.of("url", url)));
            }
        }
        return result;
    }


    public List<Map<String, Object>> buildVideoContent() {
        List<Map<String, Object>> result = new ArrayList<>(1);
        if (videoUrls != null) {
            for (String url : videoUrls) {
                result.add(Maps.of("type", "video_url").set("video_url", Maps.of("url", url)));
            }
        }
        return result;
    }


    public List<Map<String, Object>> buildImageContent() {
        List<Map<String, Object>> result = new ArrayList<>(1);
        if (imageUrls != null) {
            for (String url : imageUrls) {
                result.add(Maps.of("type", "image_url").set("image_url", Maps.of("url", url)));
            }
        }
        if (imageBase64s != null) {
            for (String base64 : imageBase64s) {
                result.add(Maps.of("type", "image_url").set("image_url", Maps.of("url", base64)));
            }
        }
        return result;
    }


    @Override
    public String toString() {
        return "UserMessage{" +
            "audioUrls=" + audioUrls +
            ", videoUrls=" + videoUrls +
            ", imageUrls=" + imageUrls +
            ", imageBase64s=" + imageBase64s +
            ", functions=" + functions +
            ", toolChoice='" + toolChoice + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
