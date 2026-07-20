/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.volcengine.tos;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageOperations;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.GetObjectV2Input;
import com.volcengine.tos.model.object.GetObjectV2Output;
import com.volcengine.tos.model.object.PutObjectInput;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class VolcengineTosObjectStorageOperations implements ObjectStorageOperations {

    private final TOSV2 client;
    private final boolean closeClient;

    VolcengineTosObjectStorageOperations(TOSV2 client, boolean closeClient) {
        this.client = client;
        this.closeClient = closeClient;
    }

    @Override
    public void put(String bucket, String key, InputStream inputStream, long contentLength) {
        client.putObject(new PutObjectInput()
            .setBucket(bucket)
            .setKey(key)
            .setContentLength(contentLength)
            .setContent(inputStream));
    }

    @Override
    public InputStream get(String bucket, String key) {
        final GetObjectV2Output output = client.getObject(new GetObjectV2Input()
            .setBucket(bucket)
            .setKey(key));
        return new FilterInputStream(output.getContent()) {
            @Override
            public void close() throws IOException {
                output.close();
            }
        };
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(new DeleteObjectInput().setBucket(bucket).setKey(key));
    }

    @Override
    public void close() {
        if (!closeClient) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to close TOS client", e);
        }
    }
}
