package com.agentsflex.search.engines.lucene;

import com.agentsflex.core.document.Document;
import com.agentsflex.search.engines.config.SearcherConfig;
import com.agentsflex.search.engines.service.DocumentSearcher;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LuceneSearcher implements DocumentSearcher {

    private static final Logger Log = LoggerFactory.getLogger(LuceneSearcher.class);

    private final String indexDirPath;
    private final Analyzer analyzer;
    private Directory directory;
    private IndexWriter indexWriter;

    public LuceneSearcher(SearcherConfig searcherConfig) {
        this.indexDirPath = searcherConfig.getIndexDirPath(); // 使用 indexName 作为索引目录路径
        this.analyzer = new StandardAnalyzer(); // 可替换为你需要的分词器

        try {
            File indexDir = new File(indexDirPath);
            if (!indexDir.exists()) {
                indexDir.mkdirs();
            }

            this.directory = FSDirectory.open(indexDir.toPath());
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            this.indexWriter = new IndexWriter(directory, config);
        } catch (IOException e) {
            Log.error("初始化 Lucene 索引失败", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean addDocument(Document document) {
        if (document == null || document.getContent() == null) return false;

        try {

            org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();
            luceneDoc.add(new StringField("id", document.getId().toString(), Field.Store.YES));
            luceneDoc.add(new TextField("content", document.getContent(), Field.Store.YES));

            if (document.getTitle() != null) {
                luceneDoc.add(new TextField("title", document.getTitle(), Field.Store.YES));
            }

            indexWriter.addDocument(luceneDoc);
            indexWriter.commit();
            return true;
        } catch (Exception e) {
            Log.error("添加文档失败", e);
            return false;
        } finally {
            close();
        }
    }

    @Override
    public boolean deleteDocument(Object id) {
        if (id == null) return false;

        try {
            Term term = new Term("id", id.toString());
            indexWriter.deleteDocuments(term);
            indexWriter.commit();
            return true;
        } catch (IOException e) {
            Log.error("删除文档失败", e);
            return false;
        } finally {
            close();
        }
    }

    @Override
    public boolean updateDocument(Document document) {
        if (document == null || document.getId() == null) return false;

        try {
            Term term = new Term("id", document.getId().toString());

            org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();
            luceneDoc.add(new StringField("id", document.getId().toString(), Field.Store.YES));
            luceneDoc.add(new TextField("content", document.getContent(), Field.Store.YES));

            if (document.getTitle() != null) {
                luceneDoc.add(new TextField("title", document.getTitle(), Field.Store.YES));
            }

            indexWriter.updateDocument(term, luceneDoc);
            indexWriter.commit();
            return true;
        } catch (IOException e) {
            Log.error("更新文档失败", e);
            return false;
        } finally {
            close();
        }
    }

    @Override
    public List<Document> searchDocuments(String keyWord) {
        List<Document> results = new ArrayList<>();

        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer); // 默认在 content 字段搜索
            Query query = parser.parse(keyWord);

            TopDocs topDocs = searcher.search(query, 10);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                org.apache.lucene.document.Document doc = searcher.doc(scoreDoc.doc);
                Document resultDoc = new Document();
                resultDoc.setId(doc.get("id"));
                resultDoc.setContent(doc.get("content"));
                resultDoc.setTitle(doc.get("title"));

                // 如果你不想要 score，可以不设置
                resultDoc.setScore((double) scoreDoc.score); // 注释掉这行即可避免 score 被赋值

                results.add(resultDoc);
            }
        } catch (Exception e) {
            Log.error("搜索文档失败", e);
        } finally {
            close();
        }

        return results;
    }

    public void close() {
        try {
            if (indexWriter != null) {
                indexWriter.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (IOException e) {
            Log.error("关闭 Lucene 失败", e);
        }
    }
}
