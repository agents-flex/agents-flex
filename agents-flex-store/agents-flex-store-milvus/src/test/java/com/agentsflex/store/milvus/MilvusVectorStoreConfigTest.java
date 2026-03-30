package com.agentsflex.store.milvus;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class MilvusVectorStoreConfigTest {

    @Test
    public void testBuilderCreate() {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
            .endpoint("http://localhost:19530")
            .token("root:Milvus")
            .database("default")
            .defaultCollectionName("test_collection")
            .dimension(1536)
            .metricType("COSINE")
            .enableDynamicField(true)
            .defaultTopK(10)
            .build();

        assertNotNull(config);
        assertEquals("http://localhost:19530", config.getEndpoint());
        assertEquals("root:Milvus", config.getToken());
        assertEquals("default", config.getDatabase());
        assertEquals("test_collection", config.getDefaultCollectionName());
        assertEquals("COSINE", config.getMetricType());
        assertTrue(config.isEnableDynamicField());
        assertEquals(10, config.getDefaultTopK());
        assertEquals("milvus", config.getType());
    }


    @Test
    public void testValidateSuccess() {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
            .endpoint("http://localhost:19530")
            .dimension(1536)
            .metricType("COSINE")
            .build();

        assertTrue(config.checkAvailable());
    }


    @Test
    public void testHttpsEndpoint() {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
            .endpoint("https://milvus.zillizcloud.com:19530")
            .build();

        assertEquals("https://milvus.zillizcloud.com:19530", config.getEndpoint());
    }

    @Test
    public void testStoreSuccess() {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
            .endpoint("http://localhost:19530")
            .defaultCollectionName("test_collection05")
            .dimension(1536)
            .build();

        Document doc1 = Document.of("文档内容 1");
        doc1.putMetadata("key", "value1");

        Document doc2 = Document.of("文档内容 2");
        doc2.putMetadata("key", "value2");

        // 准备测试数据
        List<Document> documents = Arrays.asList(
            doc1, doc2
        );

        // 设置向量（模拟已嵌入）
        for (Document doc : documents) {
            doc.setVector(new float[1536]);
        }

        MilvusVectorStore store = MilvusVectorStore.create(config);
        StoreResult result = store.store(documents);

        System.out.println(result);
    }

    @Test
    public void testSearchSuccess() {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
            .endpoint("http://localhost:19530")
            .defaultCollectionName("test_collection05")
            .dimension(1536)
            .build();


        MilvusVectorStore store = MilvusVectorStore.create(config);
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setText("文档");
        wrapper.setVector(new float[1536]);

        List<Document> search = store.search(wrapper);

        System.out.println(search);
    }
}
