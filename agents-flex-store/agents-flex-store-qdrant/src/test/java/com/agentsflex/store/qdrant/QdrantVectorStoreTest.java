package com.agentsflex.store.qdrant;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QdrantVectorStoreTest {

    @Test
    public void testSaveVectors() throws Exception {
        QdrantVectorStore db = getDb();
        StoreOptions options = new StoreOptions();
        options.setCollectionName("test_collection1");
        List<Document> list = new ArrayList<>();
        Document doc1 = new Document();
        doc1.setId(1L);
        doc1.setContent("test1");
        doc1.setVector(new float[]{5.2f, 4.4f});
        list.add(doc1);
        Document doc2 = new Document();
        doc2.setId(2L);
        doc2.setContent("test2");
        doc2.setVector(new float[]{5.2f, 3.9f});
        list.add(doc2);
        Document doc3 = new Document();
        doc3.setId(3);
        doc3.setContent("test3");
        doc3.setVector(new float[]{4.9f, 3.4f});
        list.add(doc3);
        db.store(list, options);
    }

    @Test
    public void testQuery() throws Exception {
        QdrantVectorStore db = getDb();;
        StoreOptions options = new StoreOptions();
        options.setCollectionName("test_collection1");
        SearchWrapper search = new SearchWrapper();
        search.setVector(new float[]{5.2f, 3.9f});
        //search.setText("test1");
        search.setMaxResults(1);
        List<Document> record = db.search(search, options);
        System.out.println(record);
    }

    @Test
    public void testDelete() throws Exception {
        QdrantVectorStore db = getDb();
        StoreOptions options = new StoreOptions();
        options.setCollectionName("test_collection1");
        db.delete(Collections.singletonList(3L), options);
    }

    private QdrantVectorStore getDb() throws Exception {
        QdrantVectorStoreConfig config = new QdrantVectorStoreConfig();
        config.setUri("localhost");
        config.setDefaultCollectionName("test_collection1");
        return new QdrantVectorStore(config);
    }
}
