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
import com.agentsflex.core.util.ArrayUtil;
import com.agentsflex.core.util.ClassUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描带有 {@link ToolDef} 注解的方法，并将其转换为 {@link Tool} 实例。
 */
public class ToolScanner {

    /**
     * 从指定对象实例中扫描并提取带 {@link ToolDef} 注解的方法，生成工具列表。
     *
     * @param object      对象实例（用于非静态方法）
     * @param methodNames 可选，指定要扫描的方法名；若为空则扫描所有带注解的方法
     * @return 工具列表
     */
    public static List<Tool> scan(Object object, String... methodNames) {
        return doScan(object.getClass(), object, methodNames);
    }

    /**
     * 从指定类中扫描并提取带 {@link ToolDef} 注解的静态方法，生成工具列表。
     *
     * @param clazz       类（仅扫描静态方法）
     * @param methodNames 可选，指定要扫描的方法名；若为空则扫描所有带注解的方法
     * @return 工具列表
     */
    public static List<Tool> scan(Class<?> clazz, String... methodNames) {
        return doScan(clazz, null, methodNames);
    }

    private static List<Tool> doScan(Class<?> clazz, Object object, String... methodNames) {
        clazz = ClassUtil.getUsefulClass(clazz);
        List<Method> methodList = ClassUtil.getAllMethods(clazz, method -> {
            if (object == null && !Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            if (method.getAnnotation(ToolDef.class) == null) {
                return false;
            }
            return methodNames.length == 0 || ArrayUtil.contains(methodNames, method.getName());
        });

        List<Tool> tools = new ArrayList<>();
        for (Method method : methodList) {
            MethodTool tool = new MethodTool();
            tool.setClazz(clazz);
            tool.setMethod(method);
            if (!Modifier.isStatic(method.getModifiers())) {
                tool.setObject(object);
            }
            tools.add(tool);
        }
        return tools;
    }
}
