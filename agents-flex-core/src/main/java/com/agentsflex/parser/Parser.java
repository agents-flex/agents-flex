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
package com.agentsflex.parser;

/**
 * 解析器，用于解析输入的内容，并按输出的格式进行输出
 *
 * @param <I> 输入内容
 * @param <O> 输出内容
 */
public interface Parser<I, O> {

    /**
     * 解析输入的内容
     *
     * @param content 输入的内容
     * @return 输出的内容
     */
    O parse(I content);
}
