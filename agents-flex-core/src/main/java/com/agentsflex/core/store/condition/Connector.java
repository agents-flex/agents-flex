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
package com.agentsflex.core.store.condition;

/**
 * @author michael
 */
public enum Connector {


    /**
     * AND
     */
    AND(" AND "),

    /**
     * AND NOT
     */
    AND_NOT(" AND NOT "),

    /**
     * OR
     */
    OR(" OR "),

    /**
     * OR NOT
     */
    OR_NOT(" OR NOT "),

    /**
     * NOT
     */
    NOT(" NOT "),
    ;


    private final String value;

    Connector(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
