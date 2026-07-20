/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.tencent.cos;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageOperations;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class TencentCosObjectStorageOperations implements ObjectStorageOperations {

    private final COSClient client;
    private final boolean closeClient;

    TencentCosObjectStorageOperations(COSClient client, boolean closeClient) {
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
        final COSObject object = client.getObject(bucket, key);
        return new FilterInputStream(object.getObjectContent()) {
            @Override
            public void close() throws IOException {
                object.close();
            }
        };
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(bucket, key);
    }

    @Override
    public void close() {
        if (closeClient) {
            client.shutdown();
        }
    }
}
