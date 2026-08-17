package com.agentsflex.core.test.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.splitter.AIDocumentSplitter;
import com.agentsflex.core.document.splitter.MarkdownHeaderSplitter;
import com.agentsflex.core.document.splitter.MarkdownTableSplitter;
import com.agentsflex.core.document.splitter.RegexDocumentSplitter;
import com.agentsflex.core.document.splitter.SimpleDocumentSplitter;
import com.agentsflex.core.document.splitter.SimpleTokenizeSplitter;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DocumentSplitterMetadataTest {

    @Test
    public void allSplittersShouldCopyTitleAndMetadata() {
        assertInherited(new RegexDocumentSplitter("\\n"), "one\ntwo");
        assertInherited(new SimpleDocumentSplitter(3), "one two");
        assertInherited(new SimpleTokenizeSplitter(3), "one two three");
        assertInherited(new MarkdownHeaderSplitter(1), "# One\ntext\n# Two\ntext");
        assertInherited(new MarkdownTableSplitter(20), "| A | B |\n| --- | --- |\n| 1 | 2 |");
    }

    @Test
    public void aiSplitterShouldCopyTitleAndMetadata() {
        Document source = source("first\nsecond");
        List<Document> chunks = new AIDocumentSplitter(new FixedChatModel("first---second")).split(source);

        assertFalse(chunks.isEmpty());
        for (Document chunk : chunks) {
            assertEquals("source title", chunk.getTitle());
            assertEquals("test.md", chunk.getMetadata("source"));
        }
    }

    @Test
    public void aiSplitterShouldHandleBoundarySeparators() {
        Document source = source("first");
        List<Document> chunks = new AIDocumentSplitter(new FixedChatModel("---first---")).split(source);

        assertEquals(1, chunks.size());
        assertEquals("first", chunks.get(0).getContent());
    }

    @Test
    public void aiSplitterShouldFallbackOnEmptyResponse() {
        AIDocumentSplitter splitter = new AIDocumentSplitter(new FixedChatModel("  "));
        splitter.setFallbackSplitter(new SimpleDocumentSplitter(3));

        List<Document> chunks = splitter.split(source("one two"));

        assertFalse(chunks.isEmpty());
        assertEquals("source title", chunks.get(0).getTitle());
        assertEquals("test.md", chunks.get(0).getMetadata("source"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void simpleSplitterShouldRejectNegativeOverlap() {
        new SimpleDocumentSplitter(10, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void tokenizeSplitterShouldRejectOverlapThatCanLoop() {
        SimpleTokenizeSplitter splitter = new SimpleTokenizeSplitter(10);
        splitter.setOverlapSize(10);
    }

    private void assertInherited(DocumentSplitter splitter, String content) {
        List<Document> chunks = splitter.split(source(content), chunkDocument -> {
            assertEquals("source title", chunkDocument.getTitle());
            assertFalse(chunkDocument.getContent().isEmpty());
            assertEquals("test.md", chunkDocument.getMetadata("source"));
            return "chunk-id";
        });
        assertFalse(chunks.isEmpty());
        for (Document chunk : chunks) {
            assertEquals("chunk-id", chunk.getId());
            assertEquals("source title", chunk.getTitle());
            assertEquals("test.md", chunk.getMetadata("source"));
        }
    }

    private Document source(String content) {
        Document source = Document.of(content);
        source.setTitle("source title");
        source.putMetadata("source", "test.md");
        return source;
    }

    private static class FixedChatModel implements ChatModel {
        private final String response;

        private FixedChatModel(String response) {
            this.response = response;
        }

        @Override
        public String chat(String prompt, ChatOptions options) {
            return response;
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
