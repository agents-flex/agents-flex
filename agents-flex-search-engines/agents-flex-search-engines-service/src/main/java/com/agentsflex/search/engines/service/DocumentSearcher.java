package com.agentsflex.search.engines.service;

import com.agentsflex.core.document.Document;

import java.util.List;

public interface DocumentSearcher {

    boolean addDocument( Document document);

    boolean deleteDocument( Object id);

    boolean updateDocument(Document document);

    List<Document> searchDocuments(String keyword);
}
