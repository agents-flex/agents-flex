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

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.core.util.JsonSchemaTypeMapper;
import com.agentsflex.core.util.TypeConverter;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.*;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 基于反射的方法工具实现
 *
 * @author fuhai
 * @since 2023/10/01
 */
public class MethodTool extends BaseTool {

    private Class<?> clazz;
    private Object object;
    private Method method;

    /**
     * 线程本地缓存，防止循环引用导致栈溢出
     */
    private static final ThreadLocal<Set<Class<?>>> RESOLVING_PROPERTIES =
        ThreadLocal.withInitial(HashSet::new);

    public Class<?> getClazz() {
        return clazz;
    }

    public void setClazz(Class<?> clazz) {
        this.clazz = clazz;
    }

    public Object getObject() {
        return object;
    }

    public void setObject(Object object) {
        this.object = object;
    }

    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        this.method = method;

        ToolDef toolDef = method.getAnnotation(ToolDef.class);
        this.name = toolDef.name();
        this.description = toolDef.description();

        List<MethodParameter> parameterList = new ArrayList<>();
        Parameter[] methodParameters = method.getParameters();
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        int index = 0;
        for (Parameter methodParameter : methodParameters) {
            MethodParameter parameter = getParameter(methodParameter, genericParameterTypes[index++]);
            parameterList.add(parameter);
        }
        this.parameters = parameterList.toArray(new MethodParameter[]{});
    }

    @NotNull
    private static MethodParameter getParameter(Parameter methodParameter, Type genericParameterType) {
        ToolParam toolParam = methodParameter.getAnnotation(ToolParam.class);
        MethodParameter parameter = new MethodParameter();
        parameter.setName(toolParam.name());
        parameter.setDescription(toolParam.description());

        Class<?> paramType = methodParameter.getType();
        String schemaType = JsonSchemaTypeMapper.mapToSchemaType(paramType);

        parameter.setType(schemaType);
        parameter.setTypeClass(genericParameterType);
        parameter.setRequired(toolParam.required());

        // 处理枚举
        String[] enums = toolParam.enums();
        if (enums != null && enums.length > 0) {
            parameter.setEnums(enums);
        } else if (genericParameterType instanceof Class && ((Class<?>) genericParameterType).isEnum()) {
            Object[] enumConstants = ((Class<?>) genericParameterType).getEnumConstants();
            String[] enumNames = new String[enumConstants.length];
            for (int i = 0; i < enumConstants.length; i++) {
                enumNames[i] = ((Enum<?>) enumConstants[i]).name();
            }
            parameter.setEnums(enumNames);
        }

        // 处理数组/集合类型的 items
        if (parameter.isArrayType()) {
            resolveItemsParameter(parameter, genericParameterType);
        }
        // 如果类型是 object，解析其带注解的属性
        else if (parameter.isObjectType()) {
            resolveChildren(parameter, paramType);
        }

        return parameter;
    }

    private static void resolveItemsParameter(com.agentsflex.core.model.chat.tool.Parameter parentParameter, Type genericParameterType) {
        MethodParameter itemsParameter = new MethodParameter();
        itemsParameter.setTypeClass(genericParameterType);

        if (genericParameterType instanceof ParameterizedType) {
            Type actualTypeArgument = ((ParameterizedType) genericParameterType).getActualTypeArguments()[0];

            String arrayItemType = JsonSchemaTypeMapper.resolveArrayItemType(actualTypeArgument);
            itemsParameter.setType(arrayItemType);

            if (itemsParameter.isObjectType()) {
                resolveChildren(itemsParameter, (Class<?>) actualTypeArgument);
            } else if (itemsParameter.isArrayType()) {
                resolveItemsParameter(itemsParameter, actualTypeArgument);
            }
        }

        parentParameter.setItemsParameter(itemsParameter);
    }


    /**
     * 递归解析实体类中带 @ToolParam 注解的字段
     *
     * @param clazz 要解析的类
     * @return 字段名 → schema map 的映射
     */
    private static void resolveChildren(com.agentsflex.core.model.chat.tool.Parameter parentParameter, @NotNull Class<?> clazz) {

        // 防止循环引用：如果当前类正在解析中，返回空避免死循环
        if (RESOLVING_PROPERTIES.get().contains(clazz)) {
            return;
        }
        RESOLVING_PROPERTIES.get().add(clazz);

        try {
            // 遍历所有字段（包括父类）
            for (Field field : getAllFields(clazz)) {
                // 跳过 static/transient/合成字段
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) ||
                    Modifier.isTransient(modifiers) ||
                    field.isSynthetic()) {
                    continue;
                }

                // 只解析有 @ToolParam 注解的字段
                if (!field.isAnnotationPresent(ToolParam.class)) {
                    continue;
                }

                ToolParam toolParam = field.getAnnotation(ToolParam.class);
                String fieldName = toolParam.name();  // 使用注解指定的名称
                Class<?> fieldType = field.getType();
                Type genericType = field.getGenericType();


                MethodParameter childParameter = new MethodParameter();
                childParameter.setName(fieldName);
                childParameter.setTypeClass(genericType);

                childParameter.setType(JsonSchemaTypeMapper.mapToSchemaType(fieldType));

                // 描述
                if (!toolParam.description().isEmpty()) {
                    childParameter.setDescription(toolParam.description());
                }

                // 枚举：优先使用注解配置，其次自动提取枚举类值
                if (toolParam.enums().length > 0) {
                    childParameter.setEnums(toolParam.enums());
                } else if (fieldType.isEnum()) {
                    Object[] constants = fieldType.getEnumConstants();
                    String[] names = new String[constants.length];
                    for (int i = 0; i < constants.length; i++) {
                        names[i] = ((Enum<?>) constants[i]).name();
                    }
                    childParameter.setEnums(names);
                }

                boolean required = toolParam.required();
                childParameter.setRequired(required);

                // 递归：如果字段类型是 object 且有 @ToolParam，继续解析其属性
                if (childParameter.isObjectType()) {
                    resolveChildren(childParameter, fieldType);
                }

                // 数组元素类型
                if (childParameter.isArrayType()) {
                    resolveItemsParameter(childParameter, genericType);
                }

                parentParameter.addChild(childParameter);
            }

        } finally {
            // 解析完成后移除，避免影响其他解析
            RESOLVING_PROPERTIES.get().remove(clazz);
        }
    }

    /**
     * 获取类及其父类的所有字段
     */
    @NotNull
    private static List<Field> getAllFields(@NotNull Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }

    public Object invoke(Map<String, Object> argsMap) {
        try {
            Object[] args = new Object[this.parameters.length];
            for (int i = 0; i < this.parameters.length; i++) {
                MethodParameter parameter = (MethodParameter) this.parameters[i];
                Object value = argsMap.get(parameter.getName());
                if (value instanceof JSONArray) {
                    args[i] = ((JSONArray) value).to(parameter.getTypeClass());
                } else if (value instanceof JSONObject) {
                    args[i] = ((JSONObject) value).to(parameter.getTypeClass());
                } else {
                    args[i] = TypeConverter.convert(value, parameter.getTypeClass());
                }
            }
            return method.invoke(object, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
