package com.agentsflex.store.opensearch;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.embedding.EmbeddingModel;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.store.elasticsearch.ElasticSearchVectorStore;
import com.agentsflex.store.elasticsearch.ElasticSearchVectorStoreConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author songyinyin
 */
public class ElasticSearchVectorStoreTest {

    private static ElasticSearchVectorStore getVectorStore() {
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        // config.setApiKey("bmtXRVNaRUJNMEZXZzMzcnNvSXk6MlNMVmFnT0hRVVNUSmN3UXpoNWp4Zw==");
        config.setUsername("elastic");
        config.setPassword("Dd2024a10");
        ElasticSearchVectorStore store = new ElasticSearchVectorStore(config);
        store.setEmbeddingModel(new EmbeddingModel() {
            @Override
            public VectorData embed(Document document, EmbeddingOptions options) {
                VectorData vectorData = new VectorData();
                vectorData.setVector(new double[]{0, 0});
                return vectorData;
            }
        });
        return store;
    }

    @Test
    public void test01() {
        ElasticSearchVectorStore store = getVectorStore();

        // https://opensearch.org/docs/latest/search-plugins/vector-search/#example
        List<Document> list = new ArrayList<>();
        Document doc1 = new Document();
        doc1.setId(1);
        doc1.setContent("test1");
        doc1.setVector(new double[]{5.2, 4.4});
        list.add(doc1);
        Document doc2 = new Document();
        doc2.setId(2);
        doc2.setContent("test2");
        doc2.setVector(new double[]{5.2, 3.9});
        list.add(doc2);
        Document doc3 = new Document();
        doc3.setId(3);
        doc3.setContent("test3");
        doc3.setVector(new double[]{4.9, 3.4});
        list.add(doc3);
        Document doc4 = new Document();
        doc4.setId(4);
        doc4.setContent("test4");
        doc4.setVector(new double[]{4.2, 4.6});
        list.add(doc4);
        Document doc5 = new Document();
        doc5.setId(5);
        doc5.setContent("test5");
        doc5.setVector(new double[]{3.3, 4.5});
        list.add(doc5);
        store.storeInternal(list, StoreOptions.DEFAULT);

        // 可能要等一会 才能查出结果
        SearchWrapper searchWrapper = new SearchWrapper();
        searchWrapper.setVector(new double[]{5, 4});
        searchWrapper.setMaxResults(3);
        List<Document> documents = store.searchInternal(searchWrapper, StoreOptions.DEFAULT);
        for (Document document : documents) {
            System.out.printf("id=%s, content=%s, vector=%s, metadata=%s\n",
                document.getId(), document.getContent(), Arrays.toString(document.getVector()), document.getMetadataMap());
        }

    }
}
