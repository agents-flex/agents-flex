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
package com.agentsflex.core.chain;

public class ChainContext {

    private static final ThreadLocal<Chain> TL_CHAIN = new ThreadLocal<>();

    private static final ThreadLocal<ChainNode> TL_NODE = new ThreadLocal<>();

    public static Chain getCurrentChain() {
        return TL_CHAIN.get();
    }

    public static ChainNode getCurrentNode() {
        return TL_NODE.get();
    }

    static void setChain(Chain chain) {
        TL_CHAIN.set(chain);
    }

    static void clearChain() {
        TL_CHAIN.remove();
    }

    static void setNode(ChainNode node) {
        TL_NODE.set(node);
    }

    static void clearNode() {
        TL_NODE.remove();
    }

}
