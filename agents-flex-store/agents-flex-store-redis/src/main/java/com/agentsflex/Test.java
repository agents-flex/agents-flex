package com.agentsflex;

import com.agentsflex.store.redis.entity.DistanceMetric;
import com.agentsflex.store.redis.entity.FieldSchema;
import com.agentsflex.store.redis.entity.FieldType;
import com.agentsflex.store.redis.entity.VectorDataType;
import com.agentsflex.store.redis.util.RedisVectorUtil;
import com.alibaba.fastjson.JSON;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.Document;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class Test {
    public static void main(String[] args) {
        UnifiedJedis unifiedjedis=new UnifiedJedis(new HostAndPort("xxxxxx",6379));
        RedisVectorUtil redisVectorTool=new RedisVectorUtil(unifiedjedis);
        // 定义字段结构来创建索引
        List<FieldSchema> fields = Arrays.asList(
            new FieldSchema("text", FieldType.TEXT, null, null, null),
            new FieldSchema("vector", FieldType.VECTOR, VectorDataType.FLOAT32, 4, DistanceMetric.COSINE)
        );
        // 创建一个名为 "redis-vector" 的向量索引
        redisVectorTool.createVectorIndex("redis-vector", fields);
        // 添加一个文档到索引，包含文本和向量
        java.util.Map<String, Object> document = new HashMap<>();
        document.put("text", "This is a sample text");
        document.put("vector", new float[]{0.1f, 0.2f, 0.3f, 0.4f});// 示例向量数据
        redisVectorTool.addDocumentToIndex("redis-vector", "1", document); // 假设文档ID为 "1"
        // 执行向量搜索，假设我们搜索与上面添加的向量相似的文档
        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        List<Document> searchResults = redisVectorTool.searchVector("redis-vector", queryVector, 10); // 限制返回结果为10个
        // 打印搜索结果
        searchResults.forEach(v-> System.out.println(JSON.toJSONString(v)));
    }
}
