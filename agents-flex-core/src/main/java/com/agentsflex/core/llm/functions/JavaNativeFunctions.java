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

import com.agentsflex.core.llm.functions.annotation.FunctionDef;
import com.agentsflex.core.util.ArrayUtil;
import com.agentsflex.core.util.ClassUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class JavaNativeFunctions extends ArrayList<Function> {

    public static JavaNativeFunctions from(Object object, String... methodNames) {
        return from(object.getClass(), object, methodNames);
    }

    public static JavaNativeFunctions from(Class<?> clazz, String... methodNames) {
        return from(clazz, null, methodNames);
    }

    private static JavaNativeFunctions from(Class<?> clazz, Object object, String... methodNames) {
        clazz = ClassUtil.getUsefulClass(clazz);
        List<Method> methodList = ClassUtil.getAllMethods(clazz, method -> {
            if (object == null && !Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            if (method.getAnnotation(FunctionDef.class) == null) {
                return false;
            }
            if (methodNames.length > 0) {
                return ArrayUtil.contains(methodNames, method.getName());
            }
            return true;
        });

        JavaNativeFunctions javaNativeFunctions = new JavaNativeFunctions();

        for (Method method : methodList) {
            JavaNativeFunction function = new JavaNativeFunction();
            function.setClazz(clazz);
            function.setMethod(method);

            if (!Modifier.isStatic(method.getModifiers())) {
                function.setObject(object);
            }

            javaNativeFunctions.add(function);
        }

        return javaNativeFunctions;
    }


}
