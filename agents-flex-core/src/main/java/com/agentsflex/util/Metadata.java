package com.agentsflex.util;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Metadata implements Serializable {

    protected Map<String, Object> metadataMap;

    public Object getMetadata(String key) {
        return metadataMap != null ? metadataMap.get(key) : null;
    }

    public void addMetadata(String key, Object value) {
        if (metadataMap == null) {
            metadataMap = new HashMap<>();
        }
        metadataMap.put(key, value);
    }

    public Map<String, Object> getMetadataMap() {
        return metadataMap;
    }

    public void setMetadataMap(Map<String, Object> metadataMap) {
        this.metadataMap = metadataMap;
    }
}
