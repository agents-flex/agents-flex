package com.agentsflex.store.milvus;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
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

    @Test
    public void testAll() throws InterruptedException {
        MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
            .endpoint("http://localhost:19530")
            .defaultCollectionName("test_collection_test")
            .dimension(1536)
            .build();


        Document doc1 = Document.of("文档内容 1");
        doc1.putMetadata("key", "value1");
        doc1.setVector(new float[1536]);

        MilvusVectorStore store = MilvusVectorStore.create(config);
        StoreResult result = store.store(Collections.singletonList(doc1));
        System.out.println("store ====>" + result);

        Thread.sleep(1000);

        MilvusVectorStore store2 = MilvusVectorStore.create(config);
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setText("文档1");
        wrapper.setVector(new float[1536]);

        List<Document> search = store2.search(wrapper);
        System.out.println("search ====>" + search);


        MilvusVectorStore store33 = MilvusVectorStore.create(config);
        SearchWrapper wrapper33 = new SearchWrapper();
        wrapper33.eq("id",doc1.getId());
        wrapper33.setVector(new float[1536]);

        List<Document> search33 = store33.search(wrapper33);
        System.out.println("search33 ====>" + search33);


        MilvusVectorStore store3 = MilvusVectorStore.create(config);
        doc1.setContent("文档内容 1更新");
        doc1.putMetadata("key", "value1更新");
        StoreResult update = store3.update(Collections.singletonList(doc1));
        System.out.println("update ====>" + update);

        Thread.sleep(1000);

        MilvusVectorStore store4 = MilvusVectorStore.create(config);
        SearchWrapper wrapper2 = new SearchWrapper();
        wrapper2.setText("文档1更新");
        wrapper2.setVector(new float[1536]);
        List<Document> search2 = store4.search(wrapper2);
        System.out.println("search2 ====>" + search2);

        MilvusVectorStore store5 = MilvusVectorStore.create(config);
        StoreResult delete = store5.delete(doc1.getId().toString());
        System.out.println("delete ====>" + delete);

        Thread.sleep(1000);

        MilvusVectorStore store6 = MilvusVectorStore.create(config);
        SearchWrapper wrapper3 = new SearchWrapper();
        wrapper3.setText("文档1更新");
        wrapper3.setVector(new float[1536]);
        List<Document> search3 = store6.search(wrapper3);
        System.out.println("search3 ====>" + search3);


        System.out.println(search);
    }
}
