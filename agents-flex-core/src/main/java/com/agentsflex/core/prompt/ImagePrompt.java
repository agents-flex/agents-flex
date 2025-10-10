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

import com.agentsflex.core.message.HumanImageMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.util.ImageUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ImagePrompt extends TextPrompt {

    private List<String> imageUrls;
    private List<String> imageBase64s;

    public ImagePrompt(String content) {
        super(content);
    }

    public ImagePrompt(String content, String imageUrl) {
        super(content);
        this.imageUrls = new ArrayList<>(1);
        this.imageUrls.add(imageUrl);
    }

    public ImagePrompt(String content, File imageFile) {
        super(content);
        this.imageBase64s = new ArrayList<>(1);
        this.imageBase64s.add(ImageUtil.imageFileToDataUri(imageFile));
    }

    public ImagePrompt(TextPrompt textPrompt) {
        super(textPrompt.getContent());
        setSystemMessage(textPrompt.getSystemMessage());
    }

    public ImagePrompt(TextPrompt textPrompt, String imageUrl) {
        super(textPrompt.getContent());
        setSystemMessage(textPrompt.getSystemMessage());
        this.imageUrls = new ArrayList<>(1);
        this.imageUrls.add(imageUrl);
    }

    public ImagePrompt(TextPrompt textPrompt, Collection<String> imageUrls) {
        super(textPrompt.getContent());
        setSystemMessage(textPrompt.getSystemMessage());
        this.imageUrls = new ArrayList<>(imageUrls.size());
        this.imageUrls.addAll(imageUrls);
    }

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
    public List<Message> toMessages() {
        return Collections.singletonList(new HumanImageMessage(this));
    }


    @Override
    public String toString() {
        return "ImagePrompt{" + "imageUrls=" + imageUrls + ", imageBase64s=" + imageBase64s + '}';
    }
}
