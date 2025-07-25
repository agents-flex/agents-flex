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
package com.agentsflex.search.engine.lucene;

import com.agentsflex.core.document.Document;
import com.agentsflex.search.engine.service.DocumentSearcher;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.jetbrains.annotations.NotNull;
import org.lionsoul.jcseg.ISegment;
import org.lionsoul.jcseg.analyzer.JcsegAnalyzer;
import org.lionsoul.jcseg.dic.DictionaryFactory;
import org.lionsoul.jcseg.segmenter.SegmenterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class LuceneSearcher implements DocumentSearcher {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneSearcher.class);

    private Directory directory;

    public LuceneSearcher(LuceneConfig config) {
        Objects.requireNonNull(config, "LuceneConfig 不能为 null");
        try {
            String indexDirPath = config.getIndexDirPath(); // 索引目录路径
            File indexDir = new File(indexDirPath);
            if (!indexDir.exists() && !indexDir.mkdirs()) {
                throw new IllegalStateException("can not mkdirs for path: " + indexDirPath);
            }

            this.directory = FSDirectory.open(indexDir.toPath());
        } catch (IOException e) {
            LOG.error("初始化 Lucene 索引失败", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean addDocument(Document document) {
        if (document == null || document.getContent() == null) return false;

        IndexWriter indexWriter = null;
        try {
            indexWriter = createIndexWriter();

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
            LOG.error("添加文档失败", e);
            return false;
        } finally {
            close(indexWriter);
        }
    }


    @Override
    public boolean deleteDocument(Object id) {
        if (id == null) return false;

        IndexWriter indexWriter = null;
        try {
            indexWriter = createIndexWriter();
            Term term = new Term("id", id.toString());
            indexWriter.deleteDocuments(term);
            indexWriter.commit();
            return true;
        } catch (IOException e) {
            LOG.error("删除文档失败", e);
            return false;
        } finally {
            close(indexWriter);
        }
    }

    @Override
    public boolean updateDocument(Document document) {
        if (document == null || document.getId() == null) return false;

        IndexWriter indexWriter = null;
        try {
            indexWriter = createIndexWriter();
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
            LOG.error("更新文档失败", e);
            return false;
        } finally {
            close(indexWriter);
        }
    }

    @Override
    public List<Document> searchDocuments(String keyword, int count) {
        List<Document> results = new ArrayList<>();
        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = buildQuery(keyword);
            TopDocs topDocs = searcher.search(query, count);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                org.apache.lucene.document.Document doc = searcher.doc(scoreDoc.doc);
                Document resultDoc = new Document();
                resultDoc.setId(doc.get("id"));
                resultDoc.setContent(doc.get("content"));
                resultDoc.setTitle(doc.get("title"));

                resultDoc.setScore((double) scoreDoc.score);

                results.add(resultDoc);
            }
        } catch (Exception e) {
            LOG.error("搜索文档失败", e);
        }

        return results;
    }

    private static Query buildQuery(String keyword) {
        try {
            Analyzer analyzer = createAnalyzer();

            QueryParser titleQueryParser = new QueryParser("title", analyzer);
            Query titleQuery = titleQueryParser.parse(keyword);
            BooleanClause titleBooleanClause = new BooleanClause(titleQuery, BooleanClause.Occur.SHOULD);

            QueryParser contentQueryParser = new QueryParser("content", analyzer);
            Query contentQuery = contentQueryParser.parse(keyword);
            BooleanClause contentBooleanClause = new BooleanClause(contentQuery, BooleanClause.Occur.SHOULD);

            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(titleBooleanClause)
                .add(contentBooleanClause);
            return builder.build();
        } catch (ParseException e) {
            LOG.error(e.toString(), e);
        }
        return null;
    }


    @NotNull
    private IndexWriter createIndexWriter() throws IOException {
        Analyzer analyzer = createAnalyzer();
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(analyzer);
        return new IndexWriter(directory, indexWriterConfig);
    }


    private static Analyzer createAnalyzer() {
        SegmenterConfig config = new SegmenterConfig(true);
        return new JcsegAnalyzer(ISegment.Type.NLP, config, DictionaryFactory.createSingletonDictionary(config));
    }

    public void close(IndexWriter indexWriter) {
        try {
            if (indexWriter != null) {
                indexWriter.close();
            }
        } catch (IOException e) {
            LOG.error("关闭 Lucene 失败", e);
        }
    }
}
