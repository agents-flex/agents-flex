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
package com.agentsflex.convert;

public class BooleanConverter implements IConverter<Boolean> {
    @Override
    public Boolean convert(String text) {
        String value = text.toLowerCase();
        if ("true".equals(value) || "1".equals(value)) {
            return Boolean.TRUE;
        } else if ("false".equals(value) || "0".equals(value)) {
            return Boolean.FALSE;
        } else {
            throw new RuntimeException("Can not parse to boolean type of value: " + text);
        }
    }
}
