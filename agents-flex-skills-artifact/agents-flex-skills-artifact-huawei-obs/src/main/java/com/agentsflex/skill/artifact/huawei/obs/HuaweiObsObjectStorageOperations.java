/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.huawei.obs;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageOperations;
import com.obs.services.ObsClient;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;

import java.io.IOException;
import java.io.InputStream;

final class HuaweiObsObjectStorageOperations implements ObjectStorageOperations {

    private final ObsClient client;
    private final boolean closeClient;

    HuaweiObsObjectStorageOperations(ObsClient client, boolean closeClient) {
        this.client = client;
        this.closeClient = closeClient;
    }

    @Override
    public void put(String bucket, String key, InputStream inputStream, long contentLength) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        metadata.setContentType("application/zip");
        client.putObject(bucket, key, inputStream, metadata);
    }

    @Override
    public InputStream get(String bucket, String key) {
        ObsObject object = client.getObject(bucket, key);
        return object.getObjectContent();
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(bucket, key);
    }

    @Override
    public void close() {
        if (!closeClient) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close OBS client", e);
        }
    }
}
