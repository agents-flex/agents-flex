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
package com.agentsflex.core.util;

import java.util.Collection;
import java.util.List;

public class CollectionUtil {

    private CollectionUtil() {
    }


    public static <T> boolean noItems(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }


    public static boolean hasItems(Collection<?> collection) {
        return !noItems(collection);
    }

    public static <T> T firstItem(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }
    

    public static <T> T lastItem(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1);
    }
}
