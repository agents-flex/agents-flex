package com.agentsflex.engines.test;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;

public class TestLucene {
    public static void main(String[] args) {
        try {
            // 1. 创建索引目录（在项目当前目录下）
            String indexPath = "./lucene_index";
            File indexDir = new File(indexPath);
            if (!indexDir.exists()) {
                indexDir.mkdirs();
            }

            // 2. 打开或创建 Lucene 存储目录
            Directory directory = FSDirectory.open(indexDir.toPath());

            // 3. 创建分词器和 IndexWriter 配置
            StandardAnalyzer analyzer = new StandardAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

            IndexWriter writer = new IndexWriter(directory, config);

            // 4. 创建一个文档
            Document doc = new Document();
            doc.add(new StringField("id", "123", Field.Store.YES));
            doc.add(new TextField("content", "这是一个测试内容", Field.Store.YES));

            // 5. 将文档写入索引
            writer.addDocument(doc);
            // 6. 提交更改并关闭
            writer.commit();
            writer.close();

            System.out.println("✅ 文档已写入 Lucene 索引！");
            System.out.println("📁 索引文件位置: " + indexDir.getAbsolutePath());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
