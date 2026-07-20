/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.aliyun.oss;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageOperations;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class AliyunOssObjectStorageOperations implements ObjectStorageOperations {

    private final OSSClient client;
    private final boolean closeClient;

    AliyunOssObjectStorageOperations(OSSClient client, boolean closeClient) {
        this.client = client;
        this.closeClient = closeClient;
    }

    @Override
    public void put(String bucket, String key, InputStream inputStream, long contentLength) {
        client.putObject(PutObjectRequest.newBuilder()
            .bucket(bucket)
            .key(key)
            .contentType("application/zip")
            .body(BinaryData.fromStream(inputStream, contentLength))
            .build());
    }

    @Override
    public InputStream get(String bucket, String key) {
        final GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
            .bucket(bucket)
            .key(key)
            .build());
        return new FilterInputStream(result.body()) {
            @Override
            public void close() throws IOException {
                try {
                    result.close();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Failed to close OSS object response", e);
                }
            }
        };
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.newBuilder()
            .bucket(bucket)
            .key(key)
            .build());
    }

    @Override
    public void close() {
        if (!closeClient) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close OSS client", e);
        }
    }
}
