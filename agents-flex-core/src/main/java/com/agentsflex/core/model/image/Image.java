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
package com.agentsflex.core.model.image;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.util.IOUtil;
import com.agentsflex.core.util.StringUtil;

import java.io.File;
import java.util.Arrays;
import java.util.Base64;

public class Image {

    /**
     * The base64-encoded JSON of the generated image
     */
    private String b64Json;

    /**
     * The URL of the generated image
     */
    private String url;

    /**
     * The data of image
     */
    private byte[] bytes;

    public static Image ofUrl(String url) {
        Image image = new Image();
        image.setUrl(url);
        return image;
    }

    public static Image ofBytes(byte[] bytes) {
        Image image = new Image();
        image.setBytes(bytes);
        return image;
    }

    public String getB64Json() {
        return b64Json;
    }

    public void setB64Json(String b64Json) {
        this.b64Json = b64Json;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] readBytes() {
        return bytes;
    }

    public void writeToFile(File file) {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new IllegalStateException("Can not mkdirs for path: " + file.getParentFile().getAbsolutePath());
        }
        if (this.bytes != null && this.bytes.length > 0) {
            IOUtil.writeBytes(this.bytes, file);
        } else if (this.b64Json != null) {
            byte[] bytes = Base64.getDecoder().decode(b64Json);
            IOUtil.writeBytes(bytes, file);
        } else if (StringUtil.hasText(this.url)) {
            byte[] bytes = new HttpClient().getBytes(this.url);
            IOUtil.writeBytes(bytes, file);
        }
    }

    @Override
    public String toString() {
        return "Image{" +
            "b64Json='" + b64Json + '\'' +
            ", url='" + url + '\'' +
            ", bytes=" + Arrays.toString(bytes) +
            '}';
    }
}
