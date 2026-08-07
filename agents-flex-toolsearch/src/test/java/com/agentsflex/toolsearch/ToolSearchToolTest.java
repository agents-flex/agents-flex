package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.toolsearch.memory.InMemoryToolSearchProvider;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ToolSearchToolTest {
    @Test
    @SuppressWarnings("unchecked")
    public void shouldSearchWithoutMutatingPrompt() {
        SimplePrompt prompt = new SimplePrompt("What is the weather?");
        Tool alwaysVisible = Tool.builder("currentTime", args -> "12:00")
            .description("Read the current time").build();
        Tool weather = Tool.builder("weatherLookup", args -> "sunny")
            .description("Look up weather and forecasts").build();
        prompt.addTool(alwaysVisible);

        InMemoryToolSearchProvider provider = new InMemoryToolSearchProvider();
        ToolSearchTool searchTool = ToolSearchTool.builder().provider(provider)
            .addTool(weather).build();
        prompt.addTool(searchTool);

        assertSame(provider, searchTool.getManager().getProvider());
        assertEquals(2, prompt.getTools().size());
        assertSame(alwaysVisible, prompt.getToolsMap().get("currentTime"));
        assertSame(searchTool, prompt.getToolsMap().get(ToolSearchTool.NAME));

        Map<String, Object> args = Collections.singletonMap("query", "weather forecast");
        List<String> names = (List<String>) searchTool.invoke(args);

        assertEquals(Collections.singletonList("weatherLookup"), names);
        assertEquals(2, prompt.getTools().size());
        assertNull(prompt.getToolsMap().get("weatherLookup"));
        assertNull(provider.findByName("currentTime"));
    }

    @Test
    public void shouldReturnEachSearchResultWithoutKeepingRuntimeState() {
        Tool weather = Tool.builder("weatherLookup", args -> "sunny").description("Weather forecast").build();
        Tool email = Tool.builder("sendEmail", args -> "sent").description("Email delivery").build();

        ToolSearchTool searchTool = ToolSearchTool.builder()
            .addTool(weather).addTool(email).build();

        assertEquals(Collections.singletonList("weatherLookup"),
            searchTool.invoke(Collections.singletonMap("query", "weather")));
        assertEquals(Collections.singletonList("sendEmail"),
            searchTool.invoke(Collections.singletonMap("query", "email")));
        assertEquals(Collections.emptyList(), searchTool.invoke(
            Collections.singletonMap("query", "capability that does not exist")));
    }

    @Test
    public void shouldPreserveMetadataAndIgnoreNonExecutableResults() {
        Tool weather = Tool.builder("weatherLookup", args -> "sunny").description("Forecast").build();
        ToolInfo weatherInfo = ToolInfo.from(weather);
        weatherInfo.setCategory("weather");
        InMemoryToolSearchProvider provider = new InMemoryToolSearchProvider();

        ToolInfo stale = new ToolInfo();
        stale.setName("removedTool");
        stale.setDescription("Forecast from a removed integration");
        stale.setCategory("weather");
        provider.save(stale);

        ToolSearchTool searchTool = ToolSearchTool.builder().provider(provider)
            .addTool(weather, weatherInfo).build();
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("query", "forecast");
        args.put("category", "weather");

        assertEquals(Collections.singletonList("weatherLookup"), searchTool.invoke(args));
    }

    @Test
    public void shouldRejectProviderAndManagerTogether() {
        assertThrows(IllegalStateException.class, () -> ToolSearchTool.builder()
            .provider(new InMemoryToolSearchProvider())
            .manager(new ToolSearchManager())
            .build());
    }

    @Test
    public void shouldExplainTheCompleteDiscoveryFlowInDefaultDescription() {
        ToolSearchTool searchTool = ToolSearchTool.builder().build();

        assertTrue(searchTool.getDescription().contains("currently available"));
        assertTrue(searchTool.getDescription().contains("complete definitions"));
        assertTrue(searchTool.getDescription().contains("does not execute"));
    }
}
