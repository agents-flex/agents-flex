/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public abstract class VectorStore<T extends VectorDocument> {

    public void store(T document) {
        store(Collections.singletonList(document));
    }

    public abstract void store(List<T> documents);

    public void delete(Collection<String> ids, String collectionName) {
        delete(ids, collectionName, null);
    }

    public abstract void delete(Collection<String> ids, String collectionName, String partitionName);

    public void update(T document) {
        update(Collections.singletonList(document));
    }

    public abstract void update(List<T> documents);

    public abstract List<T> search(SearchWrapper wrapper);
}
