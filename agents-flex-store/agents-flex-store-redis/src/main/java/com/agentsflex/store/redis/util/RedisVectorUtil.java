package com.agentsflex.store.redis.util;

import com.agentsflex.store.redis.entity.FieldSchema;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * xgc
 * redis向量工具类
 */
public class RedisVectorUtil {

   private  UnifiedJedis unifiedjedis;

   public RedisVectorUtil(UnifiedJedis unifiedjedis){
       this.unifiedjedis=unifiedjedis;
   }
    public void createVectorIndex(String indexName, List<FieldSchema> fields) {
        String pre= "doc:"+indexName+":";
        IndexDefinition definition = new IndexDefinition().setPrefixes(new String[]{pre});
        Schema schema = new Schema();
        for (FieldSchema field : fields) {
            switch (field.getType()){
                case VECTOR:
                    Map<String, Object> attr = new HashMap<>();
                    attr.put("TYPE", field.getDataType().name());
                    attr.put("DIM", field.getDimension());
                    attr.put("DISTANCE_METRIC", field.getMetric().name());
                    schema.addHNSWVectorField(field.getName(),attr);
                    break;
                case TEXT:
                    schema.addTextField(field.getName(),1);
                    break;
                case NUMBER:
                    schema.addNumericField(field.getName());
                    break;
            }
        }
        unifiedjedis.ftCreate(indexName, IndexOptions.defaultOptions().setDefinition(definition), schema);
    }

    public List<Document> searchVector(String indexName, float[] queryVector, int limit) {
        // 创建查询向量
        byte[] vectorBytes = new byte[queryVector.length * 4];
        ByteBuffer byteBuffer = ByteBuffer.wrap(vectorBytes);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float v : queryVector) {
            byteBuffer.putFloat(v);
        }
        // 构建查询
        Query query = new Query("*=>[KNN " + limit + " @vector $vector AS score]").addParam("vector", vectorBytes).limit(0,limit).dialect(2);
        // 执行搜索
        SearchResult results = unifiedjedis.ftSearch(indexName, query);
        // 提取文档
        return results.getDocuments();
    }

    // 向索引中添加文档
    public void addDocumentToIndex(String indexName, String docId, Map<String, Object> fields) {
        String key= "doc:"+indexName+":"+docId;
        unifiedjedis.hsetObject(key,fields);
    }
}
