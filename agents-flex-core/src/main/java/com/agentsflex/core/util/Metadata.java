/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe, serializable metadata container for storing key-value pairs.
 * <p>
 * <strong>Key Features:</strong>
 * <ul>
 *   <li>Thread-safe by default using {@link ConcurrentHashMap}</li>
 *   <li>Defensive copying to protect internal state</li>
 *   <li>Null-safe: returns empty map instead of null</li>
 *   <li>Type-safe generic getter with fallback default</li>
 *   <li>Immutable view exposure via {@link #asMap()}</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>{@code
 *   Metadata meta = new Metadata();
 *   meta.putMetadata("model", "gpt-4");
 *   meta.putMetadata("temperature", 0.7);
 *
 *   String model = meta.getMetadata("model", String.class, "default");
 *   Map<String, Object> snapshot = meta.asMap(); // unmodifiable view
 * }</pre>
 *
 * @author Michael Yang (fuhai999@gmail.com)
 * @since 2023
 */
public class Metadata implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Internal storage: ConcurrentHashMap for thread-safety.
     * Lazily initialized to save memory when unused.
     */
    protected volatile Map<String, Object> metadataMap;

    /**
     * Gets the value associated with the specified key.
     *
     * @param key the metadata key
     * @return the value, or {@code null} if not found
     */
    public Object getMetadata(String key) {
        Map<String, Object> map = metadataMap;
        return (map != null) ? map.get(key) : null;
    }

    /**
     * Gets the value associated with the specified key, or returns defaultValue if not found.
     *
     * @param key          the metadata key
     * @param defaultValue the value to return if key is absent
     * @return the value, or defaultValue if not found
     */
    public Object getMetadata(String key, Object defaultValue) {
        Object value = getMetadata(key);
        return (value != null) ? value : defaultValue;
    }

    /**
     * Type-safe getter with class check and fallback default.
     *
     * @param key          the metadata key
     * @param type         the expected type of the value
     * @param defaultValue the value to return if key is absent or type mismatch
     * @param <T>          the expected type
     * @return the casted value, or defaultValue if not found or type mismatch
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type, T defaultValue) {
        Objects.requireNonNull(type, "type must not be null");
        Object value = getMetadata(key);
        return (type.isInstance(value)) ? (T) value : defaultValue;
    }

    /**
     * Puts a metadata entry (replaces existing value if key exists).
     *
     * @param key   the metadata key
     * @param value the metadata value (must be Serializable if serialization is used)
     */
    public void putMetadata(String key, Object value) {
        getOrCreateMap().put(key, value);
    }

    /**
     * Puts all entries from the given map (replaces existing values for duplicate keys).
     *
     * @param metadata the map of metadata to add
     */
    public void putMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        getOrCreateMap().putAll(metadata);
    }

    /**
     * Puts a metadata entry only if the key is not already present.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return the previous value associated with the key, or null if there was no mapping
     */
    public Object putMetadataIfAbsent(String key, Object value) {
        return getOrCreateMap().putIfAbsent(key, value);
    }

    /**
     * Removes the metadata entry for the specified key.
     *
     * @param key the metadata key
     * @return the previous value associated with the key, or null if there was no mapping
     */
    public Object removeMetadata(String key) {
        Map<String, Object> map = metadataMap;
        return (map != null) ? map.remove(key) : null;
    }

    /**
     * Checks if metadata contains the specified key.
     *
     * @param key the metadata key
     * @return true if the key exists, false otherwise
     */
    public boolean containsMetadata(String key) {
        Map<String, Object> map = metadataMap;
        return (map != null) && map.containsKey(key);
    }

    /**
     * Checks if this metadata container is empty.
     *
     * @return true if no entries exist, false otherwise
     */
    public boolean isEmpty() {
        Map<String, Object> map = metadataMap;
        return (map == null) || map.isEmpty();
    }

    /**
     * Returns the number of metadata entries.
     *
     * @return the entry count
     */
    public int sizeOfMetadata() {
        Map<String, Object> map = metadataMap;
        return (map != null) ? map.size() : 0;
    }

    /**
     * Removes all metadata entries.
     */
    public void clearMetadata() {
        Map<String, Object> map = metadataMap;
        if (map != null) {
            map.clear();
        }
    }

    /**
     * Returns an unmodifiable view of the internal metadata map.
     * <p>
     * Changes to this Metadata instance will be reflected in the returned view,
     * but attempts to modify the view directly will throw {@link UnsupportedOperationException}.
     *
     * @return an unmodifiable map view (never null)
     */
    public Map<String, Object> asMap() {
        Map<String, Object> map = metadataMap;
        return (map != null) ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    /**
     * Replaces the internal metadata map with a defensive copy of the provided map.
     * <p>
     * This method does not merge; it replaces all existing entries.
     *
     * @param metadataMap the new metadata map (may be null to clear)
     */
    public void setMetadataMap(Map<String, ?> metadataMap) {
        if (metadataMap == null || metadataMap.isEmpty()) {
            this.metadataMap = null;
        } else {
            // Defensive copy to avoid external mutation
            this.metadataMap = new ConcurrentHashMap<>(metadataMap);
        }
    }

    /**
     * Gets or creates the internal map with lazy initialization.
     * Thread-safe via double-checked locking pattern.
     *
     * @return the internal map instance
     */
    private Map<String, Object> getOrCreateMap() {
        Map<String, Object> map = metadataMap;
        if (map == null) {
            synchronized (this) {
                map = metadataMap;
                if (map == null) {
                    map = new ConcurrentHashMap<>(8);
                    metadataMap = map;
                }
            }
        }
        return map;
    }

    // ========== Object Overrides ==========

    @Override
    public String toString() {
        return "Metadata{metadataMap=" + metadataMap + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Metadata)) return false;
        Metadata that = (Metadata) o;
        // Compare content, not reference
        return Objects.equals(this.asMap(), that.asMap());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(asMap());
    }

    // ========== Backward Compatibility Aliases ==========

    /**
     * @deprecated use {@link #putMetadata(String, Object)} for clearer semantics.
     * This method is retained for backward compatibility and will be removed in a future major version.
     */
    @Deprecated
    public void addMetadata(String key, Object value) {
        putMetadata(key, value);
    }

    /**
     * @deprecated use {@link #putMetadata(Map)} for clearer semantics.
     * This method is retained for backward compatibility and will be removed in a future major version.
     */
    @Deprecated
    public void addMetadata(Map<String, Object> metadata) {
        putMetadata(metadata);
    }

    public Map<String, Object> getMetadataMap() {
        return metadataMap;
    }
}
