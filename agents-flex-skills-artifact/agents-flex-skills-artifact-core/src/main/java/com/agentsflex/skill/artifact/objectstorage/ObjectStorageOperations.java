/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.objectstorage;

import java.io.InputStream;

/**
 * Skill Artifact 所需的最小对象存储操作集合。
 *
 * <p>{@code bucket} 表示对象所属存储桶；实现可以映射到 OSS、S3、MinIO、COS 或其他
 * 对象存储产品。该接口有意不包含列举对象、ACL、签名 URL 等非 Artifact 必需能力。</p>
 */
public interface ObjectStorageOperations extends AutoCloseable {

    /** 上传一个对象；方法返回时对象必须可以被后续读取。 */
    void put(String bucket, String key, InputStream inputStream, long contentLength);

    /** 下载一个对象；调用方负责关闭返回流。 */
    InputStream get(String bucket, String key);

    /** 幂等删除一个对象。 */
    void delete(String bucket, String key);

    /** 释放实现持有的客户端资源；不拥有客户端时可以为空操作。 */
    @Override
    void close();
}
