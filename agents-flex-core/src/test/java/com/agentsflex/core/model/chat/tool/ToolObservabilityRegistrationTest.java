package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.message.ToolCall;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ToolObservabilityRegistrationTest {

    @After
    public void clearGlobalInterceptors() {
        GlobalToolInterceptors.clear();
    }

    @Test
    public void shouldRegisterBuiltInObservabilityInterceptor() {
        ToolExecutor executor = new ToolExecutor(tool(), toolCall());

        assertTrue(executor.getInterceptors().get(0) instanceof ToolObservabilityInterceptor);
    }

    @Test
    public void shouldNotRegisterObservabilityInterceptorTwice() {
        GlobalToolInterceptors.addInterceptor(new ToolObservabilityInterceptor());

        ToolExecutor executor = new ToolExecutor(tool(), toolCall());
        int count = 0;
        for (ToolInterceptor interceptor : executor.getInterceptors()) {
            if (interceptor instanceof ToolObservabilityInterceptor) {
                count++;
            }
        }

        assertEquals(1, count);
    }

    private static Tool tool() {
        return Tool.builder("echo").function(args -> "ok").build();
    }

    private static ToolCall toolCall() {
        return new ToolCall("call-1", "echo", "{\"value\":\"ok\"}");
    }
}
