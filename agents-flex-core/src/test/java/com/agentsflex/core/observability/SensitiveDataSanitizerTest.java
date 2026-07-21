package com.agentsflex.core.observability;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SensitiveDataSanitizerTest {

    @Test
    public void shouldRedactNestedSensitiveJsonFields() {
        String input = "{\"user\":\"michael\",\"credentials\":{" +
            "\"apiKey\":\"top-secret\",\"nested\":[{\"sessionToken\":\"token-value\"}]}}";

        String sanitized = SensitiveDataSanitizer.sanitizeJson(input, 4000);

        assertTrue(sanitized.contains("\"user\":\"michael\""));
        assertFalse(sanitized.contains("top-secret"));
        assertFalse(sanitized.contains("token-value"));
    }

    @Test
    public void shouldRedactSensitiveFieldsInObjects() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("password", "do-not-export");
        nested.put("values", Arrays.asList("safe"));

        String sanitized = SensitiveDataSanitizer.sanitizeObject(nested, 4000);

        assertFalse(sanitized.contains("do-not-export"));
        assertTrue(sanitized.contains("\"password\":\"***\""));
    }

    @Test
    public void shouldNeverReturnInvalidJsonVerbatim() {
        assertEquals("[UNPARSEABLE_CONTENT_REDACTED]",
            SensitiveDataSanitizer.sanitizeJson("token=secret", 4000));
    }
}
