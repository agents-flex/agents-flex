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
package com.agentsflex.core.audio.tts;

import com.agentsflex.core.util.Metadata;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class TextToSpeechResponse extends Metadata {

    private boolean success = true;
    private String message;
    private List<byte[]> results;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<byte[]> getResults() {
        return results;
    }

    public void setResults(List<byte[]> results) {
        this.results = results;
    }

    public void addResult(byte[] bytesArray) {
        if (results == null) {
            results = new ArrayList<>();
        }
        results.add(bytesArray);
    }

    public void writeTo(File file) {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            throw new IllegalStateException("Can not mkdirs for path: " + file.getParentFile().getAbsolutePath());
        }
        try (FileOutputStream stream = new FileOutputStream(file)) {
            writeTo(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeTo(OutputStream outStream) {
        try {
            for (byte[] bytesArray : this.results) {
                outStream.write(bytesArray);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "TextToSpeechResponse{" +
            "success=" + success +
            ", message='" + message + '\'' +
            ", results=" + results +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
