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
package com.agentsflex.store.qdrant;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.StringUtil;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

public class QdrantVectorStore extends DocumentStore {

    private final QdrantVectorStoreConfig config;
    private final QdrantClient            client;
    private final String                  defaultCollectionName;
    private       boolean                 isCreateCollection = false;
    
    

    public QdrantVectorStore(QdrantVectorStoreConfig config) throws IOException {
        this.config = config;
        this.defaultCollectionName = config.getDefaultCollectionName();
        String uri = config.getUri();
        int port = 6334;
        QdrantGrpcClient.Builder builder;
        if (StringUtil.hasText(config.getCaPath())) {
            ManagedChannel channel = Grpc.newChannelBuilder(
                uri,
                TlsChannelCredentials.newBuilder().trustManager(new File(config.getCaPath())).build()
            ).build();
            builder = QdrantGrpcClient.newBuilder(channel, true);
        } else {
            if (uri.contains(":")) {
            	String[] parts = uri.split(":", 2);
            	if (parts.length == 2) {
            	    uri = parts[0].trim();
            	    try {
            	        port = Integer.parseInt(parts[1].trim());
            	        if (port < 1 || port > 65535) {
            	            throw new IllegalArgumentException("Port number out of valid range (1-65535): " + port);
            	        }
            	    } catch (NumberFormatException e) {
            	        throw new IllegalArgumentException("Invalid port format in URI: " + uri, e);
            	    }
            	} else {
            	    throw new IllegalArgumentException("Invalid URI format. Expected format: host:port, current value: " + uri);
            	}
            }
            builder = QdrantGrpcClient.newBuilder(uri, port, false);
        }
        if (StringUtil.hasText(config.getApiKey())) {
            builder.withApiKey(config.getApiKey());
        }
        this.client = new QdrantClient(builder.build());
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        List<PointStruct> points = new ArrayList<>();
        int size = 1024;
        for (Document doc : documents) {
        	UUID uuid = uuid(doc.getId());
        	doc.setId(uuid);
            size = doc.getVector().length;
            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            payload.put("content", value(doc.getContent()));
            points.add(PointStruct.newBuilder()
                .setId(id(uuid))
                .setVectors(vectors(doc.getVector()))
                .putAllPayload(payload)
                .build());
        }
        try {
            String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
            if (config.isAutoCreateCollection() && !isCreateCollection) {
                Boolean exists = client.collectionExistsAsync(collectionName).get();
                if (!exists) {
                    client.createCollectionAsync(collectionName, Collections.VectorParams.newBuilder()
                            .setDistance(Collections.Distance.Cosine)
                            .setSize(size)
                            .build())
                        .get();
                }
            } else {
                isCreateCollection = true;
            }
            if (CollectionUtil.hasItems(points)) {
                client.upsertAsync(collectionName, points).get();
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception e) {
            return StoreResult.fail("Store failed: " + e.getMessage(), e);
        }
    }

	private UUID uuid(Object id) {
		
		return md5ToUuid(id.toString());
	}
	
	private UUID md5ToUuid(String md5) {
	    // 把 32 位 MD5 转成 16 字节 byte[]
	    byte[] bytes = new byte[16];
	    for (int i = 0; i < 16; i++) {
	        bytes[i] = (byte) Integer.parseInt(md5.substring(i * 2, i * 2 + 2), 16);
	    }
	    // 拆成高 8 字节、低 8 字节
	    long msb = 0;
	    long lsb = 0;
	    for (int i = 0; i < 8; i++) {
	        msb = (msb << 8) | (bytes[i] & 0xff);
	    }
	    for (int i = 8; i < 16; i++) {
	        lsb = (lsb << 8) | (bytes[i] & 0xff);
	    }
	    return new UUID(msb, lsb);
	}

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        try {
            String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
            List<PointId> pointIds = ids.stream()
                .map(id -> id((Long) id))
                .collect(Collectors.toList());
            client.deleteAsync(collectionName, pointIds).get();
            return StoreResult.success();
        } catch (Exception e) {
            return StoreResult.fail("Delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        try {
            List<PointStruct> points = new ArrayList<>();
            for (Document doc : documents) {
                Map<String, JsonWithInt.Value> payload = new HashMap<>();
                payload.put("content", value(doc.getContent()));
                points.add(PointStruct.newBuilder()
                    .setId(id(uuid(doc)))
                    .setVectors(vectors(doc.getVector()))
                    .putAllPayload(payload)
                    .build());
            }
            String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
            if (CollectionUtil.hasItems(points)) {
                client.upsertAsync(collectionName, points).get();
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception e) {
            return StoreResult.fail("Update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        List<Document> documents = new ArrayList<>();
        try {
            String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
            QueryPoints.Builder query = QueryPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(wrapper.getMaxResults())
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(true).build())
                .setWithPayload(enable(true));
            if (wrapper.getVector() != null) {
                query.setQuery(nearest(wrapper.getVector()));
            }
            if (StringUtil.hasText(wrapper.getText())) {
                query.setFilter(Filter.newBuilder().addMust(matchKeyword("content", wrapper.getText())));
            }
            List<ScoredPoint> data = client.queryAsync(query.build()).get();
            for (ScoredPoint point : data) {
                Document doc = new Document();
                doc.setId(point.getId().getNum());
                doc.setVectorByNumbers(point.getVectors().getVector().getDataList());
                doc.setContent(point.getPayloadMap().get("content").getStringValue());
                documents.add(doc);
            }
            return documents;
        } catch (Exception e) {
            return documents;
        }
    }

    public QdrantClient getClient() {
        return client;
    }
}
