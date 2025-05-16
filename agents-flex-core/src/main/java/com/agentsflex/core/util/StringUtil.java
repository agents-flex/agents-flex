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
package com.agentsflex.core.util;

public class StringUtil {

    public static boolean noText(String string) {
        return !hasText(string);
    }

    public static boolean hasText(String string) {
        return string != null && !string.isEmpty() && containsText(string);
    }

    public static boolean hasText(String... strings) {
        for (String string : strings) {
            if (!hasText(string)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsText(CharSequence str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static String getFirstWithText(String... strings) {
        if (strings == null) {
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
        return jsonString.startsWith("{") && jsonString.endsWith("}");
    }

    public static boolean notJsonObject(String jsonString) {
        return !isJsonObject(jsonString);
    }


}
