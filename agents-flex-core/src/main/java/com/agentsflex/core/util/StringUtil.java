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
package com.agentsflex.core.util;

public class StringUtil {


    public static boolean hasText(String string) {
        return string != null && !string.isEmpty() && containsText(string);
    }


    /**
     * 所有字符串有内容时返回 true
     */
    public static boolean allHasText(String... strings) {
        if (strings == null || strings.length == 0) {
            return false;
        }
        for (String string : strings) {
            if (!hasText(string)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 任意字符串有内容时返回 true
     */
    public static boolean anyHasText(String... strings) {
        if (strings == null || strings.length == 0) {
            return false;
        }
        for (String string : strings) {
            if (hasText(string)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 字符串为 null 或者内部字符全部为 ' ', '\t', '\n', '\r' 这四类字符时返回 true
     */
    public static boolean noText(String string) {
        return !hasText(string);
    }

    /**
     * 只要有一个有内容，返回 false， 所有都没有内容时返回 true
     */
    public static boolean allNoText(String... strings) {
        if (strings == null || strings.length == 0) {
            return false;
        }
        for (String string : strings) {
            if (hasText(string)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 只要有一个没内容，返回 true，所有都有内容时返回 false
     */
    public static boolean anyNoText(String... strings) {
        if (strings == null || strings.length == 0) {
            return false;
        }
        for (String string : strings) {
            if (noText(string)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsText(CharSequence str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static String firstHasText(String... strings) {
        if (strings == null || strings.length == 0) {
            return null;
        }
        for (String str : strings) {
            if (hasText(str)) {
                return str;
            }
        }
        return null;
    }

    public static boolean isJsonObject(String jsonString) {
        if (noText(jsonString)) {
            return false;
        }

        jsonString = jsonString.trim();
        return jsonString.startsWith("{") && jsonString.endsWith("}") && jsonString.contains(":");
    }

    public static boolean notJsonObject(String jsonString) {
        return !isJsonObject(jsonString);
    }


    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        int len = str.length(), i = 0;
        if (str.charAt(0) == '+' || str.charAt(0) == '-') {
            if (len == 1) return false;
            i = 1;
        }
        for (; i < len; i++) {
            char c = str.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
