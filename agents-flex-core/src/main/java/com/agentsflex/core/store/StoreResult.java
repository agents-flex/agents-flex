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
package com.agentsflex.core.store;

import com.agentsflex.core.util.Metadata;
import com.agentsflex.core.document.Document;

import java.util.ArrayList;
import java.util.List;

public class StoreResult extends Metadata {
    private final boolean success;
    private Exception exception;
    private String message;
    private List<Object> ids;

    public StoreResult(boolean success) {
        this.success = success;
        this.message = "";
    }

    public StoreResult(boolean success, String message) {
        this.success = success;
        this.message = message == null ? "" : message;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<Object> ids() {
        return ids;
    }

    public static StoreResult fail(String message, Exception exception) {
        StoreResult storeResult = new StoreResult(false);
        storeResult.setMessage(message);
        storeResult.exception = exception;
        return storeResult;
    }

    public static StoreResult fail(String message) {
        return new StoreResult(false, message);
    }


    public static StoreResult fail() {
        return new StoreResult(false);
    }

    public static StoreResult success() {
        return new StoreResult(true);
    }

    public static StoreResult successWithIds(List<Document> documents) {
        StoreResult result = success();
        result.ids = new ArrayList<>(documents.size());
        for (Document document : documents) {
            result.ids.add(document.getId());
        }
        return result;
    }

    public Exception getException() {
        return exception;
    }

    public void setException(Exception exception) {
        this.exception = exception;
    }

    public List<Object> getIds() {
        return ids;
    }

    public void setIds(List<Object> ids) {
        this.ids = ids;
    }

    @Override
    public String toString() {
        return "StoreResult{" +
            "success=" + success +
            ", exception=" + exception +
            ", message='" + message + '\'' +
            ", ids=" + ids +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
