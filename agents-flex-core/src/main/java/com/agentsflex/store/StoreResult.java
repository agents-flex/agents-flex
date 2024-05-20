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
package com.agentsflex.store;

import com.agentsflex.document.Document;

import java.util.ArrayList;
import java.util.List;

public interface StoreResult {

    static StoreResult fail() {
        return new FaiResult();
    }

    boolean isSuccess();

    default List<Object> ids() {
        return null;
    }

    static StoreResult success() {
        return new SuccessResult();
    }

    static StoreResult successWithIds(List<Document> documents) {
        SuccessResult result = new SuccessResult();
        result.ids = new ArrayList<>(documents.size());
        for (Document document : documents) {
            result.ids.add(document.getId());
        }
        return result;
    }

    class SuccessResult implements StoreResult {

        List<Object> ids;

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public List<Object> ids() {
            return ids;
        }
    }

    class FaiResult implements StoreResult {
        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}
