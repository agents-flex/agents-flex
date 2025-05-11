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

    @Test(expected = StatusRuntimeException.class)
    public void testUseMivlusDb() {
        //准备数据
        List<Document> list = new ArrayList<>();
        Document doc1 = new Document();
        doc1.setId(1);
        doc1.setContent("test1--6");
//        doc1.setVector(new double[]{5.2, 4.4});
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("test1-6666", "test1-666");
        doc1.setMetadataMap(metadata);
        list.add(doc1);
        Document doc2 = new Document();
        doc2.setId(2);
        doc2.setContent("test2");
        doc2.setVector(new double[]{5.2, 3.9});
        list.add(doc2);

        MilvusVectorStoreConfig config = new MilvusVectorStoreConfig();
        config.setUri("http://localhost:19530");
        config.setUsername("root");
        config.setPassword("Milvus");
        config.setAutoCreateCollection(true);
        config.setDefaultCollectionName("milve_test");

        MilvusVectorStore store = new MilvusVectorStore(config);
        store.setEmbeddingModel(new EmbeddingModel() {
            @Override
            public VectorData embed(Document document, EmbeddingOptions options) {
                //如果文档没有vectorData，则使用此方法返回文档的vectorData
                VectorData vectorData = new VectorData();
                vectorData.setVector(new double[]{1, 2});
                return vectorData;
            }
        });

        //新增数据，第一次可以自动创建集合
        //milvus不支持主键去重
        store.store(list);

        //查找数据
        SearchWrapper rw = new SearchWrapper();
        rw.eq("id", "1");
        List<Document> search = store.search(rw);
        System.out.println("search = " + search);

        //删除数据
//        store.delete("1","2");
    }

}
