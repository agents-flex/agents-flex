package com.agentsflex.store.pgvector;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.util.Maps;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PgvectorDbTest {

    @Test
    public void testInsert() {
        PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
        config.setHost("127.0.0.1");
        config.setPort(5432);
        config.setDatabaseName("pgvector_test");
        config.setUsername("test");
        config.setPassword("123456");
        config.setVectorDimension(1024);
        config.setUseHnswIndex(true);
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("test");

        PgvectorVectorStore store = new PgvectorVectorStore(config);
        Document doc = new Document("测试数据");
        // 初始化 vector 为长度为 1024 的全是 1 的数组
        double[] vector = new double[1024];
        Arrays.fill(vector, 1.0);

        doc.setVector(vector);
        doc.setMetadataMap(Maps.of("test", "test"));
        store.store(doc);
    }

    @Test
    public void testInsertMany() {
        PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
        config.setHost("127.0.0.1");
        config.setPort(5432);
        config.setDatabaseName("pgvector_test");
        config.setUsername("test");
        config.setPassword("123456");
        config.setVectorDimension(1024);
        config.setUseHnswIndex(true);
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("test");

        PgvectorVectorStore store = new PgvectorVectorStore(config);
        List<Document> docs = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            Document doc = new Document("测试数据" + i);
            // 初始化 vector 为长度为 1024 的全是 1 的数组
            double[] vector = new double[1024];
            Arrays.fill(vector, Math.random());

            doc.setVector(vector);
            doc.setMetadataMap(Maps.of("test", "test" + i));
            docs.add(doc);
        }

        store.store(docs);
    }

    @Test
    public void testSearch() {
        PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
        config.setHost("127.0.0.1");
        config.setPort(5432);
        config.setDatabaseName("pgvector_test");
        config.setUsername("test");
        config.setPassword("123456");
        config.setVectorDimension(1024);
        config.setUseHnswIndex(true);
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("test");
        PgvectorVectorStore store = new PgvectorVectorStore(config);

        double[] vector = new double[1024];
        Arrays.fill(vector, 1.0);

        SearchWrapper searchWrapper = new SearchWrapper().text("测试数据");
        searchWrapper.setVector(vector);
        searchWrapper.setMinScore(0.0);
        searchWrapper.setOutputVector(true);
        List<Document> docs = store.search(searchWrapper);
        System.out.println(docs);
    }

    @Test
    public void testUpdate() {
        PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
        config.setHost("127.0.0.1");
        config.setPort(5432);
        config.setDatabaseName("pgvector_test");
        config.setUsername("test");
        config.setPassword("123456");
        config.setVectorDimension(1024);
        config.setUseHnswIndex(true);
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("test");
        PgvectorVectorStore store = new PgvectorVectorStore(config);

        Document document = new Document("测试数据");
        document.setId("145314895749100ae8306079519b3393");
        document.setMetadataMap(Maps.of("test", "test0"));
        double[] vector = new double[1024];
        Arrays.fill(vector, 1.1);
        document.setVector(vector);
        StoreResult update = store.update(document);
        System.out.println(update);
    }

    @Test
    public void testDelete() {
        PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
        config.setHost("127.0.0.1");
        config.setPort(5432);
        config.setDatabaseName("pgvector_test");
        config.setUsername("test");
        config.setPassword("123456");
        config.setVectorDimension(1024);
        config.setUseHnswIndex(true);
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("test");
        PgvectorVectorStore store = new PgvectorVectorStore(config);

        StoreResult update = store.delete("145314895749100ae8306079519b3393","e83518d36b6d5de8199b40e3ef4e4ce1");
        System.out.println(update);
    }
}
