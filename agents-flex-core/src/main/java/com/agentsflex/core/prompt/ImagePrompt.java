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
package com.agentsflex.core.prompt;

import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.util.ImageUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ImagePrompt extends TextPrompt {

    private String imageUrl;
    private File imageFile;
    private String imageBase64;

    public ImagePrompt(String content) {
        super(content);
    }

    public ImagePrompt(String content, String imageUrl) {
        super(content);
        this.imageUrl = imageUrl;
    }

    public ImagePrompt(String content, File imageFile) {
        super(content);
        this.imageFile = imageFile;
    }

    public ImagePrompt(String content, InputStream imageStream) {
        super(content);
        this.imageBase64 = ImageUtil.imageStreamToBase64(imageStream);
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public File getImageFile() {
        return imageFile;
    }

    public void setImageFile(File imageFile) {
        this.imageFile = imageFile;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }


    public String toUrl() {
        if (StringUtil.hasText(imageUrl)) {
            return imageUrl;
        }

        if (imageBase64 != null) {
            return imageBase64;
        }

        if (imageFile != null) {
            imageBase64 = ImageUtil.imageFileToBase64(imageFile);
            return imageBase64;
        }
        return null;
    }


    public String toImageBase64() {
        if (imageBase64 != null) {
            return imageBase64;
        }

        if (StringUtil.hasText(imageUrl)) {
            imageBase64 = ImageUtil.imageUrlToBase64(imageUrl);
            return imageBase64;
        }

        if (imageFile != null) {
            imageBase64 = ImageUtil.imageFileToBase64(imageFile);
            return imageBase64;
        }

        return null;
    }

    @Override
    public List<Message> toMessages() {
        return Collections.singletonList(new TextAndImageMessage(this));
    }


    @Override
    public String toString() {
        return "ImagePrompt{" +
            "imageUrl='" + imageUrl + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }


    public static class TextAndImageMessage extends HumanMessage {

        private final ImagePrompt prompt;

        public TextAndImageMessage(ImagePrompt prompt) {
            this.prompt = prompt;
        }

        public ImagePrompt getPrompt() {
            return prompt;
        }

        @Override
        public Object getMessageContent() {
            List<Map<String, Object>> messageContent = new ArrayList<>();
            messageContent.add(Maps.of("type", "text").set("text", prompt.content));
            messageContent.add(Maps.of("type", "image_url").set("image_url", Maps.of("url", prompt.toUrl())));
            return messageContent;
        }

    }
}
