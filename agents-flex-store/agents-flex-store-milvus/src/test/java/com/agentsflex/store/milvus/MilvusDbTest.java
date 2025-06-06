package com.agentsflex.store.milvus;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.embedding.EmbeddingModel;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.VectorData;
import io.grpc.StatusRuntimeException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MilvusDbTest {

    /**
     * Create a MilvusVectorStore with standard test configuration
     */
    private MilvusVectorStore createTestStore() {
        // Create configuration
        MilvusVectorStoreConfig config = new MilvusVectorStoreConfig();
        config.setUri("http://localhost:19530");
        config.setUsername("root");
        config.setPassword("Milvus");
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("milve_test");

        // Create store
        MilvusVectorStore store = new MilvusVectorStore(config);
        
        // Add embedding model
        store.setEmbeddingModel(new EmbeddingModel() {
            @Override
            public VectorData embed(Document document, EmbeddingOptions options) {
                // Generate vector for document
                VectorData vectorData = new VectorData();
                vectorData.setVector(new double[]{Math.random(), Math.random()});
                return vectorData;
            }

            @Override
            public int dimensions() {
                return 2;
            }
        });
        
        return store;
    }

    /**
     * Create test documents
     */
    private List<Document> createTestDocuments() {
        List<Document> list = new ArrayList<>();
        
        Document doc1 = new Document();
        doc1.setId(1);
        doc1.setContent("Technical document content");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("test1-6666", "test1-666");
        doc1.setMetadataMap(metadata);
        list.add(doc1);
        
        Document doc2 = new Document();
        doc2.setId(2);
        doc2.setContent("Lifestyle document content");
        Map<String, Object> metadata2 = new HashMap<>();
        metadata2.put("test2-7777", "test2-777");
        doc2.setMetadataMap(metadata2);
        list.add(doc2);
        
        return list;
    }



    @Test(expected = StatusRuntimeException.class)
    public void testUseMivlusDb() {
        MilvusVectorStore store = createTestStore();
        List<Document> documents = createTestDocuments();

        // Store data
        store.store(documents);
        System.out.println("Data storage completed");

        // Delete data
        // store.delete(1, 2);
    }

    @Test(expected = StatusRuntimeException.class)
    public void testVectorSearch() {
        MilvusVectorStore store = createTestStore();

        // Test vector search (using text)
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setText("Lifestyle");
        wrapper.setMaxResults(10);

        List<Document> results = store.search(wrapper);
        System.out.println("Vector search results count: " + results.size());
        for (Document doc : results) {
            System.out.println("Vector search result - ID: " + doc.getId() + 
                ", Score: " + doc.getScore() + ", Content: " + doc.getContent());
        }
    }

    @Test(expected = StatusRuntimeException.class)
    public void testNonVectorSearch() {
        MilvusVectorStore store = createTestStore();

        // Test non-vector search (condition query)
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.eq("id", 1);

        List<Document> results = store.search(wrapper);
        System.out.println("Non-vector search results count: " + results.size());
        for (Document doc : results) {
            System.out.println("Non-vector search result - ID: " + doc.getId() + 
                ", Content: " + doc.getContent());
        }
    }

}
