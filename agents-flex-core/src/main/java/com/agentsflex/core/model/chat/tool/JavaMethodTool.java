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

import java.lang.reflect.*;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * 基于反射的方法工具实现
 *
 * @author fuhai
 * @since 2023/10/01
 */
public class JavaMethodTool extends BaseTool {

    private Class<?> clazz;
    private Object object;
    private Method method;

    /**
     * 线程本地缓存，防止循环引用导致栈溢出
     */
    private static final ThreadLocal<Set<Class<?>>> RESOLVING_PROPERTIES =  ThreadLocal.withInitial(HashSet::new);

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

        List<TypedParameter> list = new ArrayList<>();

        Parameter[] methodParameters = method.getParameters();
        Type[] genericTypes = method.getGenericParameterTypes();

        for (int i = 0; i < methodParameters.length; i++) {

            Parameter p = methodParameters[i];
            Type genericType = genericTypes[i];

            ToolParam tp = p.getAnnotation(ToolParam.class);
            if (tp == null){
                throw new RuntimeException("@ToolParam annotation not fund in method:" + method.getName());
            }

            TypedParameter param = new TypedParameter();

            param.setName(tp.name());
            param.setDescription(tp.description());
            param.setRequired(tp.required());

            Class<?> raw = p.getType();

            param.setType(JsonSchemaTypeMapper.mapToSchemaType(raw));
            param.setTypeClass(genericType);

            if (param.isObjectType()) {
                List<TypedParameter> children = new ArrayList<>();
                ToolParameterResolver.resolveChildren(children, raw);
                children.forEach(param::addChild);
            }

            if (param.isArrayType()) {
                ToolParameterResolver.resolveArray(param, genericType);
            }

            list.add(param);
        }

        this.parameters = list.toArray(new TypedParameter[0]);
    }


    @Override
    public Object invoke(Map<String, Object> argsMap) {
        try {
            Object[] args = new Object[this.parameters.length];
            for (int i = 0; i < this.parameters.length; i++) {
                TypedParameter parameter = (TypedParameter) this.parameters[i];
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

    @Override
    public String toString() {
        return "JavaMethodTool{" +
            "clazz=" + clazz +
            ", object=" + object +
            ", method=" + method +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", parameters=" + Arrays.toString(parameters) +
            '}';
    }
}
