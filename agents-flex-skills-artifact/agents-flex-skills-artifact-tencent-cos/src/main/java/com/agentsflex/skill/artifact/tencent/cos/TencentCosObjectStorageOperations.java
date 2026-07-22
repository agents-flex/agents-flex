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
