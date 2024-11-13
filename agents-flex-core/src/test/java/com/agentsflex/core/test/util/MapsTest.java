package com.agentsflex.core.test.util;

import com.agentsflex.core.util.Maps;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class MapsTest {

    @Test
    public void testMaps() {
        Map<String, Object> map1 = Maps.of("key", "value")
            .putChild("options.aaa", 1);

        Assert.assertEquals(1, ((Map<?, ?>) map1.get("options")).get("aaa"));
        System.out.println(map1);
    }
}
