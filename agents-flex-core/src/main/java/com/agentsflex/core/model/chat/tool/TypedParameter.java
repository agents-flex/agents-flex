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
package com.agentsflex.core.model.chat.tool;

import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * 方法参数，继承 Parameter 并增加类型信息
 *
 * @author fuhai
 * @since 2023/10/01
 */
public class TypedParameter extends Parameter {

    private static final long serialVersionUID = 1L;

    /**
     * 参数的完整类型信息（支持泛型）
     */
    protected Type typeClass;

    public Type getTypeClass() {
        return typeClass;
    }

    public void setTypeClass(Type typeClass) {
        this.typeClass = typeClass;
    }

    @Override
    public String toString() {
        return "TypedParameter{" +
            "typeClass=" + typeClass +
            ", name='" + name + '\'' +
            ", type='" + type + '\'' +
            ", description='" + description + '\'' +
            ", enums=" + Arrays.toString(enums) +
            ", required=" + required +
            ", defaultValue=" + defaultValue +
            ", children=" + children +
            ", itemsParameter=" + itemsParameter +
            '}';
    }
}
