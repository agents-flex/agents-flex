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
package com.agentsflex.core.file2text.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class IOUtils {
    private static final int BUFFER_SIZE = 8192;

    public static byte[] toByteArray(InputStream is, long maxSize) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copyStream(is, buffer, maxSize);
        return buffer.toByteArray();
    }

    public static void copyStream(InputStream is, OutputStream os, long maxSize) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long total = 0;
        try {
            while ((bytesRead = is.read(buffer)) != -1) {
                if (total + bytesRead > maxSize) {
                    throw new RuntimeException("Stream too large: limit is " + maxSize + " bytes");
                }
                os.write(buffer, 0, bytesRead);
                total += bytesRead;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
