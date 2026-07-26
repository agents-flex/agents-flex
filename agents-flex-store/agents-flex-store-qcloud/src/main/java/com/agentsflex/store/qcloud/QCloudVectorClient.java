/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.qcloud;

import com.tencent.tcvectordb.model.Document;
import com.tencent.tcvectordb.model.param.dml.DeleteParam;
import com.tencent.tcvectordb.model.param.dml.InsertParam;
import com.tencent.tcvectordb.model.param.dml.QueryParam;
import com.tencent.tcvectordb.model.param.dml.SearchByVectorParam;
import com.tencent.tcvectordb.model.param.dml.UpdateParam;
import com.tencent.tcvectordb.model.param.entity.AffectRes;

import java.util.List;

/** 为腾讯云官方 SDK 提供可替换、可测试的最小调用边界。 */
interface QCloudVectorClient extends AutoCloseable {

    AffectRes upsert(String database, String collection, InsertParam param);

    AffectRes delete(String database, String collection, DeleteParam param);

    AffectRes update(String database, String collection, UpdateParam param, Document document);

    List<List<Document>> search(String database, String collection, SearchByVectorParam param);

    List<Document> query(String database, String collection, QueryParam param);

    @Override
    void close();
}
