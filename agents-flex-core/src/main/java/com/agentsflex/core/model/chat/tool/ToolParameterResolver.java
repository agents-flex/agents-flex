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

import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.core.util.JsonSchemaTypeMapper;

import java.lang.reflect.*;
import java.util.*;

public class ToolParameterResolver {

    private static final ThreadLocal<Set<Class<?>>> RESOLVING = ThreadLocal.withInitial(HashSet::new);

    /**
     * 对外入口：解析 DTO → Parameter[]
     */
    public static Parameter[] resolve(Class<?> type) {
        if (type == null) {
            return new Parameter[0];
        }

        List<TypedParameter> result = new ArrayList<>();
        resolveChildren(result, type);
        return result.toArray(new Parameter[0]);
    }

    // =========================
    // 解析对象字段
    // =========================
    public static void resolveChildren(List<TypedParameter> list, Class<?> clazz) {

        if (RESOLVING.get().contains(clazz)) {
            return;
        }

        RESOLVING.get().add(clazz);

        try {
            for (Field field : getAllFields(clazz)) {

                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) {
                    continue;
                }

                if (!field.isAnnotationPresent(ToolParam.class)) {
                    continue;
                }

                ToolParam tp = field.getAnnotation(ToolParam.class);

                TypedParameter param = new TypedParameter();

                param.setName(tp.name().isEmpty() ? field.getName() : tp.name());

                param.setDescription(tp.description());
                param.setRequired(tp.required());

                Class<?> fieldType = field.getType();
                Type genericType = field.getGenericType();

                param.setType(JsonSchemaTypeMapper.mapToSchemaType(fieldType));
                param.setTypeClass(genericType);

                // enum
                resolveEnum(param, tp, fieldType);

                // object
                if (param.isObjectType()) {
                    List<TypedParameter> children = new ArrayList<>();
                    resolveChildren(children, fieldType);
                    children.forEach(param::addChild);
                }

                // array
                if (param.isArrayType()) {
                    resolveArray(param, genericType);
                }

                list.add(param);
            }

        } finally {
            RESOLVING.get().remove(clazz);
        }
    }

    // =========================
    // array
    // =========================
    public static void resolveArray(TypedParameter parent, Type type) {

        TypedParameter items = new TypedParameter();
        items.setTypeClass(type);

        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type arg = pt.getActualTypeArguments()[0];

            Class<?> raw = (Class<?>) (arg instanceof Class ? arg : Object.class);

            items.setType(JsonSchemaTypeMapper.resolveArrayItemType(arg));

            if (items.isObjectType()) {
                List<TypedParameter> children = new ArrayList<>();
                resolveChildren(children, raw);
                children.forEach(items::addChild);
            }

            if (items.isArrayType()) {
                resolveArray(items, arg);
            }
        }

        parent.setItemsParameter(items);
    }

    // =========================
    // enum
    // =========================
    private static void resolveEnum(TypedParameter param, ToolParam tp, Class<?> type) {

        if (tp.enums().length > 0) {
            param.setEnums(tp.enums());
            return;
        }

        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            String[] names = new String[constants.length];

            for (int i = 0; i < constants.length; i++) {
                names[i] = ((Enum<?>) constants[i]).name();
            }

            param.setEnums(names);
        }
    }

    // =========================
    // fields
    // =========================
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Collections.addAll(list, current.getDeclaredFields());
            current = current.getSuperclass();
        }

        return list;
    }
}
