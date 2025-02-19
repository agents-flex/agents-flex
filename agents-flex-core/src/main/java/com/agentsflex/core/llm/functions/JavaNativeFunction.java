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
package com.agentsflex.core.llm.functions;

import com.agentsflex.core.convert.ConvertService;
import com.agentsflex.core.llm.functions.annotation.FunctionDef;
import com.agentsflex.core.llm.functions.annotation.FunctionParam;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JavaNativeFunction extends BaseFunction {

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

        FunctionDef functionDef = method.getAnnotation(FunctionDef.class);
        this.name = functionDef.name();
        this.description = functionDef.description();

        List<JavaNativeParameter> parameterList = new ArrayList<>();
        java.lang.reflect.Parameter[] methodParameters = method.getParameters();
        for (java.lang.reflect.Parameter methodParameter : methodParameters) {
            JavaNativeParameter parameter = getParameter(methodParameter);
            parameterList.add(parameter);
        }
        this.parameters = parameterList.toArray(new JavaNativeParameter[]{});
    }

    @NotNull
    private static JavaNativeParameter getParameter(java.lang.reflect.Parameter methodParameter) {
        FunctionParam functionParam = methodParameter.getAnnotation(FunctionParam.class);
        JavaNativeParameter parameter = new JavaNativeParameter();
        parameter.setName(functionParam.name());
        parameter.setDescription(functionParam.description());
        parameter.setType(methodParameter.getType().getSimpleName().toLowerCase());
        parameter.setTypeClass(methodParameter.getType());
        parameter.setRequired(functionParam.required());
        parameter.setEnums(functionParam.enums());
        return parameter;
    }

    public Object invoke(Map<String, Object> argsMap) {
        try {
            Object[] args = new Object[this.parameters.length];
            for (int i = 0; i < this.parameters.length; i++) {
                JavaNativeParameter parameter = (JavaNativeParameter) this.parameters[i];
                Object value = argsMap.get(parameter.getName());
                args[i] = ConvertService.convert(value, parameter.getTypeClass());
            }
            return method.invoke(object, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
