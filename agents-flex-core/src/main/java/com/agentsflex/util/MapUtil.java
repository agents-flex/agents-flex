package com.agentsflex.util;

import java.util.HashMap;
import java.util.Map;

public class MapUtil {

    public static Map<String, Object> ofSingleKey(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    public static MapBuilder of() {
        return new MapBuilder();
    }

    public static MapBuilder of(String key, Object value) {
        return new MapBuilder().put(key, value);
    }

    public static class MapBuilder {
        private Map<String, Object> map = new HashMap<>();

        public MapBuilder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }
    }

}
