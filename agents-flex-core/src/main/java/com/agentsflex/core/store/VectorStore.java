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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 向量存储的统一抽象。
 *
 * <p>提供单条和批量数据的新增、删除、更新、检索入口。未显式传入
 * {@link StoreOptions} 的便捷方法统一使用 {@link StoreOptions#DEFAULT}。</p>
 *
 * @param <T> 向量数据类型
 */
public abstract class VectorStore<T extends VectorData> {

    /**
     * 存储单条向量数据，使用默认存储选项。
     *
     * @param vectorData The Vector Data
     * @return Store Result
     */
    public StoreResult store(T vectorData) {
        return store(vectorData, StoreOptions.DEFAULT);
    }

    /**
     * 使用指定选项存储单条向量数据。
     *
     * @param vectorData The Vector Data
     * @param options    Store Options
     * @return Store Result
     */
    public StoreResult store(T vectorData, StoreOptions options) {
        return store(Collections.singletonList(vectorData), options);
    }

    /**
     * 批量存储向量数据，使用默认存储选项。
     *
     * @param vectorDataList The Vector Data List
     * @return Store Result
     */
    public StoreResult store(List<T> vectorDataList) {
        return store(vectorDataList, StoreOptions.DEFAULT);
    }

    /**
     * 使用指定选项批量存储向量数据，由具体存储实现完成。
     *
     * @param vectorDataList vector data list
     * @param options        options
     * @return store result
     */
    public abstract StoreResult store(List<T> vectorDataList, StoreOptions options);


    /**
     * 按字符串 ID 批量删除数据，使用默认存储选项。
     *
     * @param ids the data ids
     * @return store result
     */
    public StoreResult delete(String... ids) {
        return delete(Arrays.asList(ids), StoreOptions.DEFAULT);
    }


    /**
     * 按数字 ID 批量删除数据，使用默认存储选项。
     *
     * @param ids the data ids
     * @return store result
     */
    public StoreResult delete(Number... ids) {
        return delete(Arrays.asList(ids), StoreOptions.DEFAULT);
    }


    /**
     * 按 ID 集合批量删除数据，使用默认存储选项。
     *
     * @param ids the ids
     * @return store result
     */
    public StoreResult delete(Collection<?> ids) {
        return delete(ids, StoreOptions.DEFAULT);
    }

    /**
     * 使用指定选项按 ID 集合批量删除数据，由具体存储实现完成。
     *
     * @param ids     ids
     * @param options store options
     * @return store result
     */
    public abstract StoreResult delete(Collection<?> ids, StoreOptions options);

    /**
     * 更新单条向量数据，使用默认存储选项。
     *
     * @param vectorData the vector data
     * @return store result
     */
    public StoreResult update(T vectorData) {
        return update(vectorData, StoreOptions.DEFAULT);
    }


    /**
     * 使用指定选项更新单条向量数据。
     *
     * @param vectorData vector data
     * @param options    store options
     * @return store result
     */
    public StoreResult update(T vectorData, StoreOptions options) {
        return update(Collections.singletonList(vectorData), options);
    }

    /**
     * 批量更新向量数据，使用默认存储选项。
     *
     * @param vectorDataList vector data list
     * @return store result
     */
    public StoreResult update(List<T> vectorDataList) {
        return update(vectorDataList, StoreOptions.DEFAULT);
    }

    /**
     * 使用指定选项批量更新向量数据，由具体存储实现完成。
     *
     * @param vectorDataList vector data list
     * @param options        store options
     * @return store result
     */
    public abstract StoreResult update(List<T> vectorDataList, StoreOptions options);

    /**
     * 按查询参数检索向量数据，使用默认存储选项。
     *
     * @param wrapper SearchWrapper
     * @return the vector data list
     */
    public List<T> search(SearchWrapper wrapper) {
        return search(wrapper, StoreOptions.DEFAULT);
    }


    /**
     * 使用指定选项检索向量数据，由具体存储实现完成。
     *
     * @param wrapper SearchWrapper
     * @param options Store Options
     * @return the vector data list
     */
    public abstract List<T> search(SearchWrapper wrapper, StoreOptions options);
}
