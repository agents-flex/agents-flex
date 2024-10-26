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
package com.agentsflex.core.store;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Vector Store
 *
 * @param <T> The Vector Data
 */
public abstract class VectorStore<T extends VectorData> {

    /**
     * Store Vector Data
     *
     * @param vectorData The Vector Data
     * @return Store Result
     */
    public StoreResult store(T vectorData) {
        return store(vectorData, StoreOptions.DEFAULT);
    }

    /**
     * Store Vector Data With Options
     *
     * @param vectorData The Vector Data
     * @param options    Store Options
     * @return Store Result
     */
    public StoreResult store(T vectorData, StoreOptions options) {
        return store(Collections.singletonList(vectorData), options);
    }

    /**
     * Store Vector Data List
     *
     * @param vectorDataList The Vector Data List
     * @return Store Result
     */
    public StoreResult store(List<T> vectorDataList) {
        return store(vectorDataList, StoreOptions.DEFAULT);
    }

    /**
     * Store Vector Data list wit options
     *
     * @param vectorDataList vector data list
     * @param options        options
     * @return store result
     */
    public abstract StoreResult store(List<T> vectorDataList, StoreOptions options);


    /**
     * delete store data by ids
     *
     * @param ids the data ids
     * @return store result
     */
    public StoreResult delete(String... ids) {
        return delete(Arrays.asList(ids), StoreOptions.DEFAULT);
    }


    /**
     * delete store data by ids
     *
     * @param ids the data ids
     * @return store result
     */
    public StoreResult delete(Number... ids) {
        return delete(Arrays.asList(ids), StoreOptions.DEFAULT);
    }


    /**
     * delete store data by id collection
     *
     * @param ids the ids
     * @return store result
     */
    public StoreResult delete(Collection<?> ids) {
        return delete(ids, StoreOptions.DEFAULT);
    }

    /**
     * delete store data by ids with options
     *
     * @param ids     ids
     * @param options store options
     * @return store result
     */
    public abstract StoreResult delete(Collection<?> ids, StoreOptions options);

    /**
     * update the vector data by id
     *
     * @param vectorData the vector data
     * @return store result
     */
    public StoreResult update(T vectorData) {
        return update(vectorData, StoreOptions.DEFAULT);
    }


    /**
     * update the vector data by id with options
     *
     * @param vectorData vector data
     * @param options    store options
     * @return store result
     */
    public StoreResult update(T vectorData, StoreOptions options) {
        return update(Collections.singletonList(vectorData), options);
    }

    /**
     * update vector data list
     *
     * @param vectorDataList vector data list
     * @return store result
     */
    public StoreResult update(List<T> vectorDataList) {
        return update(vectorDataList, StoreOptions.DEFAULT);
    }

    /**
     * update store data list with options
     *
     * @param vectorDataList vector data list
     * @param options        store options
     * @return store result
     */
    public abstract StoreResult update(List<T> vectorDataList, StoreOptions options);

    /**
     * search vector data by SearchWrapper
     *
     * @param wrapper SearchWrapper
     * @return the vector data list
     */
    public List<T> search(SearchWrapper wrapper) {
        return search(wrapper, StoreOptions.DEFAULT);
    }


    /**
     * search vector data by SearchWrapper with options
     *
     * @param wrapper SearchWrapper
     * @param options Store Options
     * @return the vector data list
     */
    public abstract List<T> search(SearchWrapper wrapper, StoreOptions options);
}
