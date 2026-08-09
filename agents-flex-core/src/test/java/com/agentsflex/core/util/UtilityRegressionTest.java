package com.agentsflex.core.util;

import com.alibaba.fastjson2.JSONPath;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

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

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
