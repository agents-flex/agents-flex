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
package com.agentsflex.core.llm.image;

import java.util.Arrays;

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

    @Override
    public String toString() {
        return "Image{" +
            "b64Json='" + b64Json + '\'' +
            ", url='" + url + '\'' +
            ", bytes=" + Arrays.toString(bytes) +
            '}';
    }
}
