package com.agentsflex.toolsearch.semantic;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.toolsearch.ToolInfo;
import com.agentsflex.toolsearch.ToolSearchRequest;
import com.agentsflex.toolsearch.ToolSearchResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SemanticToolSearchProviderTest {

    @Test
    public void shouldRecallSemanticallyRelatedToolWithoutKeywordOverlap() {
        RecordingEmbeddingModel embeddings = new RecordingEmbeddingModel();
        SemanticToolSearchProvider provider = new SemanticToolSearchProvider(embeddings);
        provider.save(info(tool("sendSlack", "Post a message to a Slack channel", "text"), "messaging"));
        provider.save(info(tool("weatherLookup", "Fetch weather forecasts", "city"), "weather"));

        List<ToolSearchResult> results = provider.search(
            new ToolSearchRequest("notify my coworker in the team chat"));

        assertEquals(1, results.size());
        assertEquals("sendSlack", results.get(0).getToolInfo().getName());
        assertEquals(Collections.singletonList("semantic"), results.get(0).getMatchedFields());
    }

    @Test
    public void shouldCacheToolEmbeddingsAndEmbedEachQueryOnce() {
        RecordingEmbeddingModel embeddings = new RecordingEmbeddingModel();
        SemanticToolSearchProvider provider = new SemanticToolSearchProvider(embeddings, -1.0d);
        provider.save(info(tool("sendSlack", "Post a message to Slack", "text"), "messaging"));
        provider.save(info(tool("weatherLookup", "Fetch weather forecasts", "city"), "weather"));
        assertEquals(2, embeddings.calls.get());

        provider.search(new ToolSearchRequest("team chat"));
        assertEquals(3, embeddings.calls.get());
        provider.search(new ToolSearchRequest("forecast"));
        assertEquals(4, embeddings.calls.get());
    }

    @Test
    public void shouldPreferExactToolNameOverSemanticNeighbor() {
        SemanticToolSearchProvider provider = new SemanticToolSearchProvider(new RecordingEmbeddingModel(), -1.0d);
        provider.save(info(tool("sendSlack", "Post a message to Slack", "text"), "messaging"));
        provider.save(info(tool("sendTeams", "Post a message to Microsoft Teams", "text"), "messaging"));

        List<ToolSearchResult> results = provider.search(new ToolSearchRequest("sendSlack"));

        assertEquals("sendSlack", results.get(0).getToolInfo().getName());
        assertEquals(Collections.singletonList("name"), results.get(0).getMatchedFields());
        assertEquals(1.0d, results.get(0).getScore(), 0.0d);
    }

    @Test
    public void shouldApplyCategoryThresholdLimitAndCrudOperations() {
        SemanticToolSearchProvider provider = new SemanticToolSearchProvider(new RecordingEmbeddingModel(), 0.8d);
        provider.save(info(tool("sendSlack", "Post a message to Slack", "text"), "messaging"));
        provider.save(info(tool("sendEmail", "Send an email", "body"), "email"));
        provider.save(info(tool("weatherLookup", "Fetch weather forecasts", "city"), "weather"));

        ToolSearchRequest request = new ToolSearchRequest("team chat");
        request.setCategory("MESSAGING");
        request.setMaxResults(1);
        List<ToolSearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("sendSlack", results.get(0).getToolInfo().getName());
        assertTrue(provider.remove("sendSlack"));
        assertNull(provider.findByName("sendSlack"));
        assertEquals(2, provider.findAll().size());
        provider.clear();
        assertTrue(provider.findAll().isEmpty());
    }

    @Test
    public void shouldNotEmbedArbitraryMetadataAndShouldIncludeParameters() {
        ToolInfo info = info(tool("createTicket", "Create a support ticket", "priority"), "support");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("apiKey", "secret-value");
        info.setMetadata(metadata);

        String text = SemanticToolSearchProvider.searchableText(info);

        assertTrue(text.contains("createTicket"));
        assertTrue(text.contains("priority"));
        assertTrue(text.contains("support"));
        assertFalse(text.contains("secret-value"));
        assertFalse(text.contains("apiKey"));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectMismatchedEmbeddingDimensions() {
        SemanticToolSearchProvider provider = new SemanticToolSearchProvider(new MismatchedEmbeddingModel(), -1.0d);
        provider.save(info(tool("sendSlack", "Post a message to Slack", "text"), "messaging"));
        provider.search(new ToolSearchRequest("team chat"));
    }

    private static ToolInfo info(Tool tool, String category) {
        ToolInfo info = ToolInfo.from(tool);
        info.setCategory(category);
        info.setTags(Arrays.asList("integration", category));
        return info;
    }

    private static Tool tool(String name, String description, String parameterName) {
        return Tool.builder(name).description(description)
            .addParameter(Parameter.builder().name(parameterName)
                .type("string").description("Value for " + parameterName).build())
            .function(args -> name).build();
    }

    private static final class RecordingEmbeddingModel implements EmbeddingModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public VectorData embed(Document document, EmbeddingOptions options) {
            calls.incrementAndGet();
            String text = document.getContent() == null ? "" : document.getContent().toLowerCase();
            if (text.contains("slack") || text.contains("team chat") || text.contains("coworker")) {
                return vector(1.0f, 0.0f, 0.0f);
            }
            if (text.contains("teams")) return vector(0.95f, 0.05f, 0.0f);
            if (text.contains("weather") || text.contains("forecast")) return vector(0.0f, 1.0f, 0.0f);
            if (text.contains("email")) return vector(0.0f, 0.0f, 1.0f);
            return vector(0.0f, 0.0f, 0.0f);
        }
    }

    private static final class MismatchedEmbeddingModel implements EmbeddingModel {
        private int calls;

        @Override
        public VectorData embed(Document document, EmbeddingOptions options) {
            calls++;
            return calls == 1 ? vector(1.0f, 0.0f) : vector(1.0f, 0.0f, 0.0f);
        }
    }

    private static VectorData vector(float... values) {
        VectorData data = new VectorData();
        data.setVector(values);
        return data;
    }
}
