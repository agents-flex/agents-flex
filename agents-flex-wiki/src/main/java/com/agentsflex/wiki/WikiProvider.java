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
package com.agentsflex.wiki;

public interface WikiProvider {

    /**
     * Loads one Wiki by path. The path comes from model/tool input and must be
     * treated as untrusted data by implementations, especially file-backed
     * providers. Implementations should reject traversal and normalize paths
     * before accessing external storage.
     *
     * @param path non-blank Wiki path
     * @return the Wiki, or {@code null} when no Wiki exists at that path
     */
    Wiki getWiki(String path);

}
