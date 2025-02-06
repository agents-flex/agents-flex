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

import okio.BufferedSink;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class IOUtil {

    public static void writeBytes(byte[] bytes, File toFile) {
        try (FileOutputStream stream = new FileOutputStream(toFile)) {
            stream.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] readBytes(File file) {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            return readBytes(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] readBytes(InputStream inputStream) {
        try {
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            copy(inputStream, outStream);
            return outStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void copy(InputStream inputStream, BufferedSink sink) throws IOException {
        byte[] buffer = new byte[1024];
        for (int len; (len = inputStream.read(buffer)) != -1; ) {
            sink.write(buffer, 0, len);
        }
    }

    public static void copy(InputStream inputStream, OutputStream outStream) throws IOException {
        byte[] buffer = new byte[1024];
        for (int len; (len = inputStream.read(buffer)) != -1; ) {
            outStream.write(buffer, 0, len);
        }
    }

    public static String readUtf8(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        copy(inputStream, outStream);
        return new String(outStream.toByteArray(), StandardCharsets.UTF_8);
    }


}
