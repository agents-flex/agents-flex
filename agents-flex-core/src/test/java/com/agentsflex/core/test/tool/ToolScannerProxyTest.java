package com.agentsflex.core.test.tool;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ToolScannerProxyTest {

    @Test
    public void scansAllInterfacesOfJdkProxy() {
        Object proxy = Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{UnrelatedApi.class, ToolApi.class},
            (instance, method, args) -> "pong"
        );

        List<Tool> tools = ToolScanner.scan(proxy);

        assertEquals(1, tools.size());
        assertEquals("ping", tools.get(0).getName());
        assertEquals("pong", tools.get(0).invoke(Collections.emptyMap()));
    }

    public interface UnrelatedApi {
        String unrelated();
    }

    public interface ToolApi {
        @ToolDef(name = "ping", description = "Returns pong")
        String ping();
    }
}
