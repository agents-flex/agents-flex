package com.agentsflex.core.util;

import com.agentsflex.core.file2text.util.EncodingDetectUtil;
import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UtilityRegressionTest {

    @Test
    public void readLongParsesNumericStrings() {
        JSONObject object = new JSONObject();
        object.put("value", "12345");

        assertEquals(Long.valueOf(12345L), JSONUtil.readLong(object, "$.value"));
        assertEquals(Long.valueOf(12345L), JSONUtil.readLong(object, JSONPath.of("$.value")));
    }

    @Test
    public void signedImageUrlKeepsItsMimeType() {
        assertEquals("image/png", ImageUtil.guessMimeTypeFromName(
            "https://example.com/image.png?token=abc#preview"));
        assertEquals("image/webp", ImageUtil.guessMimeTypeFromName(
            "https://example.com/image.webp?X-Amz-Signature=abc"));
    }

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

    @Test
    public void totalTimeoutRejectsLateSuccessfulResult() {
        Retryer retryer = Retryer.builder()
            .maxRetries(0)
            .totalTimeoutMs(5)
            .build();

        try {
            retryer.execute(() -> {
                Thread.sleep(25);
                return "late";
            });
            fail("Expected retry timeout");
        } catch (RetryException expected) {
            assertTrue(hasCause(expected, java.util.concurrent.TimeoutException.class));
        }
    }

    @Test
    public void interruptedStatusIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            Retryer.builder().build().execute(() -> "unused");
            fail("Expected interrupted retry");
        } catch (RetryException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
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

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
