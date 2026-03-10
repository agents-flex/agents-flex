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
import com.agentsflex.core.util.TypeConverter;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MethodTool extends BaseTool {

    private Class<?> clazz;
    private Object object;
    private Method method;

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
        java.lang.reflect.Parameter[] methodParameters = method.getParameters();
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        int index = 0;
        for (java.lang.reflect.Parameter methodParameter : methodParameters) {
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
        parameter.setType(mapJavaTypeToJsonSchemaType(paramType));
        parameter.setTypeClass(genericParameterType);
        parameter.setRequired(toolParam.required());

        // For array/collection types, set up the items schema
        if ("array".equals(parameter.getType())) {
            String arrayItemType = getArrayItemType(genericParameterType);
            MethodParameter itemParam = new MethodParameter();
            itemParam.setType(arrayItemType);
            itemParam.setDescription("Array items");
            parameter.addChild(itemParam);
        }

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
        return parameter;
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

    /**
     * Maps Java types to JSON Schema types
     * Valid JSON Schema types: string, number, integer, boolean, object, array, null
     */
    private static String mapJavaTypeToJsonSchemaType(Class<?> javaType) {
        if (javaType == null) {
            return "string";
        }

        // Handle array types
        if (javaType.isArray()) {
            return "array";
        }

        String typeName = javaType.getSimpleName();

        // Map collection types to array
        if (java.util.List.class.isAssignableFrom(javaType) ||
            java.util.Collection.class.isAssignableFrom(javaType)) {
            return "array";
        }

        // Map numeric types
        switch (typeName) {
            case "int":
            case "Integer":
            case "Long":
            case "long":
            case "Short":
            case "short":
            case "Byte":
            case "byte":
                return "integer";
            case "Float":
            case "float":
            case "Double":
            case "double":
                return "number";
            case "Boolean":
            case "boolean":
                return "boolean";
            case "String":
            case "string":
                return "string";
            case "Object":
            case "object":
                return "object";
            default:
                // For Map and other complex types, default to object
                if (java.util.Map.class.isAssignableFrom(javaType)) {
                    return "object";
                }
                // Default to string for unknown types
                return "string";
        }
    }

    /**
     * Determines the JSON Schema type for array items based on generic type info
     */
    private static String getArrayItemType(Type genericType) {
        if (genericType == null) {
            return "string";
        }

        // Handle ParameterizedType (e.g., List<String>, List<Object>)
        if (genericType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType pType = (java.lang.reflect.ParameterizedType) genericType;
            Type[] typeArgs = pType.getActualTypeArguments();
            if (typeArgs.length > 0) {
                Type itemType = typeArgs[0];
                if (itemType instanceof Class) {
                    return mapJavaTypeToJsonSchemaType((Class<?>) itemType);
                }
            }
        }

        // For raw List or List<Object>, default to object (most permissive)
        return "object";
    }
}
