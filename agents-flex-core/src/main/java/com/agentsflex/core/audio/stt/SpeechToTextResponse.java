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
package com.agentsflex.core.audio.stt;

import com.agentsflex.core.util.Metadata;

public class SpeechToTextResponse extends Metadata {

    private boolean success = true;
    private String message;
    private String result;

    public static SpeechToTextResponse of(String result) {
        SpeechToTextResponse response = new SpeechToTextResponse();
        response.setResult(result);
        return response;
    }

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

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public void addResult(String result) {
        if (this.result == null) {
            this.result = result;
        } else {
            this.result = this.result + result;
        }
    }

    @Override
    public String toString() {
        return "SpeechToTextResponse{" +
            "success=" + success +
            ", message='" + message + '\'' +
            ", result='" + result + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
