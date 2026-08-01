package com.agentsflex.core.agent.context;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 外置内容在 Artifact Store 中的稳定引用。 */
public final class AgentArtifactReference implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String artifactId;
    private final String runId;
    private final String mediaType;
    private final long size;
    private final String checksum;
    private final Map<String, Object> metadata;

    public AgentArtifactReference(String artifactId, String runId, String mediaType,
                                  long size, String checksum, Map<String, ?> metadata) {
        this.artifactId = artifactId;
        this.runId = runId;
        this.mediaType = mediaType;
        this.size = size;
        this.checksum = checksum;
        this.metadata = metadata == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    public String getArtifactId() { return artifactId; }
    public String getRunId() { return runId; }
    public String getMediaType() { return mediaType; }
    public long getSize() { return size; }
    public String getChecksum() { return checksum; }
    public Map<String, Object> getMetadata() { return metadata; }
}
