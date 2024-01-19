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
package com.agentsflex.functions;

import com.agentsflex.functions.annotation.FunctionDef;
import com.agentsflex.functions.annotation.FunctionParam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Function<R> {
    private Class<?> clazz;
    private Method method;
    private String name;
    private String description;
    private Parameter[] parameters;

    public Class<?> getClazz() {
        return clazz;
    }

    public void setClazz(Class<?> clazz) {
        this.clazz = clazz;
    }

    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        this.method = method;

        FunctionDef functionDef = method.getAnnotation(FunctionDef.class);
        this.name = functionDef.name();
        this.description = functionDef.description();

        List<Parameter> parameterList = new ArrayList<>();
        java.lang.reflect.Parameter[] methodParameters = method.getParameters();
        for (java.lang.reflect.Parameter methodParameter : methodParameters) {
            FunctionParam functionParam = methodParameter.getAnnotation(FunctionParam.class);
            Parameter parameter = new Parameter();
            parameter.setName(functionParam.name());
            parameter.setDescription(functionParam.description());
            parameter.setType(methodParameter.getType().getSimpleName().toLowerCase());
            parameter.setRequired(functionParam.required());
            parameter.setEnums(functionParam.enums());
            parameterList.add(parameter);
        }
        this.parameters = parameterList.toArray(new Parameter[]{});
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public void setParameters(Parameter[] parameters) {
        this.parameters = parameters;
    }

    public R invoke(Map<String, Object> argsMap) {
        try {
            Object[] args = new Object[this.parameters.length];
            for (int i = 0; i < this.parameters.length; i++) {
                args[i] = argsMap.get(this.parameters[i].getName());
            }
            //noinspection unchecked
            return (R) method.invoke(null, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
