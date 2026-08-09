package com.agentsflex.doc.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class EncodingDetectUtilTest {

    @Test
    public void utf8BomIsNotReturnedAsContent() throws Exception {
        byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bytes, 3, text.length);

        assertEquals("hello", readAll(EncodingDetectUtil.getAutoDetectReader(
            new ByteArrayInputStream(bytes))));
    }

    @Test
    public void utf32LeBomIsDetectedBeforeUtf16Le() throws Exception {
        byte[] bytes = new byte[]{
            (byte) 0xFF, (byte) 0xFE, 0, 0,
            'A', 0, 0, 0
        };

        assertEquals("A", readAll(EncodingDetectUtil.getAutoDetectReader(
            new ByteArrayInputStream(bytes))));
    }

    private static String readAll(Reader reader) throws Exception {
        try (Reader closeable = reader) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[32];
            int read;
            while ((read = closeable.read(buffer)) >= 0) {
                result.append(buffer, 0, read);
            }
            return result.toString();
        }
    }
}
