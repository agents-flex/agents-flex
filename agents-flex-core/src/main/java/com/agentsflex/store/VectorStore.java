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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 向量存储
 *
 * @param <T>
 */
public abstract class VectorStore<T extends VectorData> {

    public StoreResult store(T document) {
        return store(document, StoreOptions.DEFAULT);
    }

    public StoreResult store(T document, StoreOptions options) {
        return store(Collections.singletonList(document), options);
    }

    public StoreResult store(List<T> documents) {
        return store(documents, StoreOptions.DEFAULT);
    }

    public abstract StoreResult store(List<T> documents, StoreOptions options);


    public StoreResult delete(Object... ids) {
        return delete(Arrays.asList(ids), StoreOptions.DEFAULT);
    }

    public StoreResult delete(Collection<Object> ids) {
        return delete(ids, StoreOptions.DEFAULT);
    }

    public abstract StoreResult delete(Collection<Object> ids, StoreOptions options);

    public StoreResult update(T document) {
        return update(document, StoreOptions.DEFAULT);
    }

    public StoreResult update(T document, StoreOptions options) {
        return update(Collections.singletonList(document), options);
    }

    public StoreResult update(List<T> documents) {
        return update(documents, StoreOptions.DEFAULT);
    }

    public abstract StoreResult update(List<T> documents, StoreOptions options);

    public List<T> search(SearchWrapper wrapper) {
        return search(wrapper, StoreOptions.DEFAULT);
    }

    public abstract List<T> search(SearchWrapper wrapper, StoreOptions options);
}
