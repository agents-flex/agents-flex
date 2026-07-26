package com.agentsflex.toolsearch.memory;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.toolsearch.ToolInfo;
import com.agentsflex.toolsearch.ToolSearchRequest;
import com.agentsflex.toolsearch.ToolSearchResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InMemoryToolSearchProviderTest {
    @Test
    public void shouldRankNameAboveDescriptionAndSearchParameters() {
        InMemoryToolSearchProvider provider = new InMemoryToolSearchProvider();
        provider.save(info(tool("weatherLookup", "Fetch a forecast", "city"), "weather"));
        provider.save(info(tool("travelPlanner", "Plan travel using weather forecasts", "destination"), "travel"));
        provider.save(info(tool("calendar", "Create an event", "timezone"), "office"));

        List<ToolSearchResult> results = provider.search(new ToolSearchRequest("weather"));
        assertEquals("weatherLookup", results.get(0).getToolInfo().getName());
        assertEquals("calendar", provider.search(new ToolSearchRequest("timezone"))
            .get(0).getToolInfo().getName());
    }

    @Test
    public void shouldApplyCategoryLimitAndCrudOperations() {
        InMemoryToolSearchProvider provider = new InMemoryToolSearchProvider();
        provider.save(info(tool("sendSlack", "Send a message", "text"), "messaging"));
        provider.save(info(tool("sendEmail", "Send a message", "body"), "email"));
        ToolSearchRequest request = new ToolSearchRequest("send message");
        request.setCategory("messaging");
        request.setMaxResults(1);

        assertEquals("sendSlack", provider.search(request).get(0).getToolInfo().getName());
        assertTrue(provider.remove("sendSlack"));
        assertNull(provider.findByName("sendSlack"));
        assertEquals(1, provider.findAll().size());
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
                .description("Value for " + parameterName).build())
            .function(args -> name).build();
    }
}
