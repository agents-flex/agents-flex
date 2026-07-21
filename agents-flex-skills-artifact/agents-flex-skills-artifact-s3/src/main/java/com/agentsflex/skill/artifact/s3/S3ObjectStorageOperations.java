/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.s3;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageOperations;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

final class S3ObjectStorageOperations implements ObjectStorageOperations {

    private final S3Client client;
    private final boolean closeClient;

    S3ObjectStorageOperations(S3Client client, boolean closeClient) {
        this.client = client;
        this.closeClient = closeClient;
    }

    @Override
    public void put(String bucket, String key, InputStream inputStream, long contentLength) {
        client.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/zip")
                .build(),
            RequestBody.fromInputStream(inputStream, contentLength));
    }

    @Override
    public InputStream get(String bucket, String key) {
        ResponseInputStream<GetObjectResponse> response = client.getObject(GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build());
        return response;
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build());
    }

    @Override
    public void close() {
        if (closeClient) {
            client.close();
        }
    }
}
