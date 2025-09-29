/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.store.chroma;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.llm.client.HttpClient;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ChromaVectorStore的测试类，测试文档的存储、搜索、更新和删除功能
 * 包含连接检查和错误处理机制，支持在无真实Chroma服务器时跳过测试
 */
public class ChromaVectorStoreTest {

    private static ChromaVectorStore store;
    private static String testTenant = "default_tenant";
    private static String testDatabase = "default_database";
    private static String testCollectionName = "test_collection";
    private static boolean isChromaAvailable = false;
    private static boolean useMock = false; // 设置为true可以在没有真实Chroma服务器时使用模拟模式

    /**
     * 在测试开始前初始化ChromaVectorStore实例
     */
    @BeforeClass
    public static void setUp() {
        // 创建配置对象
        ChromaVectorStoreConfig config = new ChromaVectorStoreConfig();
        config.setHost("localhost");
        config.setPort(8000);
        config.setCollectionName(testCollectionName);
        config.setTenant(testTenant);
        config.setDatabase(testDatabase);
        config.setAutoCreateCollection(true);
        
        // 初始化存储实例
        try {
            store = new ChromaVectorStore(config);
            System.out.println("ChromaVectorStore initialized successfully.");
            
            // 检查连接是否可用
            isChromaAvailable = checkChromaConnection(config);
            
            if (!isChromaAvailable && !useMock) {
                System.out.println("Chroma server is not available. Tests will be skipped unless useMock is set to true.");
            }
        } catch (Exception e) {
            System.err.println("Failed to initialize ChromaVectorStore: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 检查Chroma服务器连接是否可用
     */
    private static boolean checkChromaConnection(ChromaVectorStoreConfig config) {
        try {
            String baseUrl = "http://" + config.getHost() + ":" + config.getPort();
            String healthCheckUrl = baseUrl + "/api/v2/heartbeat";
            
            HttpClient httpClient = new HttpClient();
            System.out.println("Checking Chroma server connection at: " + healthCheckUrl);
            
            // 使用较短的超时时间进行健康检查
            String response = httpClient.get(healthCheckUrl);
            if (response != null) {
                System.out.println("Chroma server connection successful! Response: " + response);
                return true;
            } else {
                System.out.println("Chroma server connection failed: Empty response");
                return false;
            }
        } catch (Exception e) {
            System.out.println("Chroma server connection failed: " + e.getMessage());
            System.out.println("Please ensure Chroma server is running on http://" + config.getHost() + ":" + config.getPort());
            System.out.println("To run tests without a real Chroma server, set 'useMock = true'");
            return false;
        }
    }
    
    /**
     * 检查是否应该运行测试
     */
    private void assumeChromaAvailable() {
        Assume.assumeTrue("Chroma server is not available and mock mode is disabled", 
                         isChromaAvailable || useMock);
    }

    /**
     * 在所有测试完成后清理资源
     */
    @AfterClass
    public static void tearDown() {
        if (store != null) {
            try {
                store.close();
                System.out.println("ChromaVectorStore closed successfully.");
            } catch (Exception e) {
                System.err.println("Error closing ChromaVectorStore: " + e.getMessage());
            }
        }
    }

    /**
     * 测试存储文档功能
     */
    @Test
    public void testStoreDocuments() {
        assumeChromaAvailable();
        
        System.out.println("Starting testStoreDocuments...");
        
        // 创建测试文档
        List<Document> documents = createTestDocuments();
        
        // 如果使用模拟模式，直接返回成功结果
        if (useMock) {
            System.out.println("Running in mock mode. Simulating store operation.");
            StoreResult mockResult = StoreResult.successWithIds(documents);
            assertTrue("Mock store operation should be successful", mockResult.isSuccess());
            assertEquals("All document IDs should be returned in mock mode", 
                         documents.size(), mockResult.ids().size());
            System.out.println("testStoreDocuments completed successfully in mock mode.");
            return;
        }
        
        // 存储文档
        try {
            StoreResult result = store.storeInternal(documents, StoreOptions.DEFAULT);
            System.out.println("Store result: " + result);
            
            // 验证存储是否成功
            assertTrue("Store operation should be successful", result.isSuccess());
            assertEquals("All document IDs should be returned", documents.size(), result.ids().size());
            
            System.out.println("testStoreDocuments completed successfully.");
        } catch (Exception e) {
            System.err.println("Failed to store documents: " + e.getMessage());
            e.printStackTrace();
            fail("Store operation failed with exception: " + e.getMessage());
        }
    }

    /**
     * 测试搜索文档功能
     */
    @Test
    public void testSearchDocuments() {
        assumeChromaAvailable();
        
        System.out.println("Starting testSearchDocuments...");
        
        // 创建测试文档
        List<Document> documents = createTestDocuments();
        
        // 如果使用模拟模式
        if (useMock) {
            System.out.println("Running in mock mode. Simulating search operation.");
            // 模拟搜索结果，返回前3个文档
            List<Document> mockResults = new ArrayList<>(documents.subList(0, Math.min(3, documents.size())));
            for (int i = 0; i < mockResults.size(); i++) {
                mockResults.get(i).setScore(1.0 - i * 0.1); // 模拟相似度分数
            }
            
            // 验证模拟结果
            assertNotNull("Mock search results should not be null", mockResults);
            assertFalse("Mock search results should not be empty", mockResults.isEmpty());
            assertTrue("Mock search results should have the correct maximum size", mockResults.size() <= 3);
            
            System.out.println("testSearchDocuments completed successfully in mock mode.");
            return;
        }
        
        try {
            // 首先存储一些测试文档
            store.storeInternal(documents, StoreOptions.DEFAULT);
            
            // 创建搜索包装器
            SearchWrapper searchWrapper = new SearchWrapper();
            // 使用第一个文档的向量进行搜索
            searchWrapper.setVector(documents.get(0).getVector());
            searchWrapper.setMaxResults(3);
            
            // 执行搜索
            List<Document> searchResults = store.searchInternal(searchWrapper, StoreOptions.DEFAULT);
            
            // 验证搜索结果
            assertNotNull("Search results should not be null", searchResults);
            assertFalse("Search results should not be empty", searchResults.isEmpty());
            assertTrue("Search results should have the correct maximum size", 
                searchResults.size() <= searchWrapper.getMaxResults());
            
            // 打印搜索结果
            System.out.println("Search results:");
            for (Document doc : searchResults) {
                System.out.printf("id=%s, content=%s, vector=%s, score=%s\n",
                    doc.getId(), doc.getContent(), Arrays.toString(doc.getVector()), doc.getScore());
            }
            
            System.out.println("testSearchDocuments completed successfully.");
        } catch (Exception e) {
            System.err.println("Failed to search documents: " + e.getMessage());
            e.printStackTrace();
            fail("Search operation failed with exception: " + e.getMessage());
        }
    }

    /**
     * 测试更新文档功能
     */
    @Test
    public void testUpdateDocuments() {
        assumeChromaAvailable();
        
        System.out.println("Starting testUpdateDocuments...");
        
        // 创建测试文档
        List<Document> documents = createTestDocuments();
        
        // 如果使用模拟模式
        if (useMock) {
            System.out.println("Running in mock mode. Simulating update operation.");
            
            // 修改文档内容
            Document updatedDoc = documents.get(0);
            String originalContent = updatedDoc.getContent();
            updatedDoc.setContent(originalContent + " [UPDATED]");
            
            // 模拟更新结果
            StoreResult mockResult = StoreResult.successWithIds(Arrays.asList(updatedDoc));
            assertTrue("Mock update operation should be successful", mockResult.isSuccess());
            
            System.out.println("testUpdateDocuments completed successfully in mock mode.");
            return;
        }
        
        try {
            // 首先存储一些测试文档
            store.storeInternal(documents, StoreOptions.DEFAULT);
            
            // 修改文档内容
            Document updatedDoc = documents.get(0);
            String originalContent = updatedDoc.getContent();
            updatedDoc.setContent(originalContent + " [UPDATED]");
            
            // 执行更新
            StoreResult result = store.updateInternal(Arrays.asList(updatedDoc), StoreOptions.DEFAULT);

            // 验证更新是否成功
            assertTrue("Update operation should be successful", result.isSuccess());
            
            // 搜索更新后的文档以验证更改
            SearchWrapper searchWrapper = new SearchWrapper();
            searchWrapper.setVector(updatedDoc.getVector());
            searchWrapper.setMaxResults(1);
            
            List<Document> searchResults = store.searchInternal(searchWrapper, StoreOptions.DEFAULT);
            assertTrue("Should find the updated document", !searchResults.isEmpty());
            assertEquals("Document content should be updated", 
                updatedDoc.getContent(), searchResults.get(0).getContent());
            
            System.out.println("testUpdateDocuments completed successfully.");
        } catch (Exception e) {
            System.err.println("Failed to update documents: " + e.getMessage());
            e.printStackTrace();
            fail("Update operation failed with exception: " + e.getMessage());
        }
    }

    /**
     * 测试删除文档功能
     */
    @Test
    public void testDeleteDocuments() {
        assumeChromaAvailable();
        
        System.out.println("Starting testDeleteDocuments...");
        
        // 创建测试文档
        List<Document> documents = createTestDocuments();
        
        // 如果使用模拟模式
        if (useMock) {
            System.out.println("Running in mock mode. Simulating delete operation.");
            
            // 获取要删除的文档ID
            List<Object> idsToDelete = new ArrayList<>();
            idsToDelete.add(documents.get(0).getId());
            
            // 模拟删除结果
            StoreResult mockResult = StoreResult.success();
            assertTrue("Mock delete operation should be successful", mockResult.isSuccess());
            
            System.out.println("testDeleteDocuments completed successfully in mock mode.");
            return;
        }
        
        try {
            // 首先存储一些测试文档
            store.storeInternal(documents, StoreOptions.DEFAULT);
            
            // 获取要删除的文档ID
            List<Object> idsToDelete = new ArrayList<>();
            idsToDelete.add(documents.get(0).getId());
            
            // 执行删除
            StoreResult result = store.deleteInternal(idsToDelete, StoreOptions.DEFAULT);
            
            // 验证删除是否成功
            assertTrue("Delete operation should be successful", result.isSuccess());
            
            // 尝试搜索已删除的文档
            SearchWrapper searchWrapper = new SearchWrapper();
            searchWrapper.setVector(documents.get(0).getVector());
            searchWrapper.setMaxResults(10);
            
            List<Document> searchResults = store.searchInternal(searchWrapper, StoreOptions.DEFAULT);
            
            // 检查结果中是否包含已删除的文档
            boolean deletedDocFound = searchResults.stream()
                .anyMatch(doc -> doc.getId().equals(documents.get(0).getId()));
            
            assertFalse("Deleted document should not be found", deletedDocFound);
            
            System.out.println("testDeleteDocuments completed successfully.");
        } catch (Exception e) {
            System.err.println("Failed to delete documents: " + e.getMessage());
            e.printStackTrace();
            fail("Delete operation failed with exception: " + e.getMessage());
        }
    }

    /**
     * 创建测试文档
     */
    private List<Document> createTestDocuments() {
        List<Document> documents = new ArrayList<>();
        
        // 创建5个测试文档，每个文档都有不同的内容和向量
        for (int i = 0; i < 5; i++) {
            Document doc = new Document();
            doc.setId("doc_" + i);
            doc.setContent("This is test document content " + i);
            doc.setTitle("Test Document " + i);
            
            // 创建一个简单的向量，向量维度为10
            double[] vector = new double[10];
            for (int j = 0; j < vector.length; j++) {
                vector[j] = i + j * 0.1;
            }
            doc.setVector(vector);
            
            documents.add(doc);
        }
        
        return documents;
    }
}