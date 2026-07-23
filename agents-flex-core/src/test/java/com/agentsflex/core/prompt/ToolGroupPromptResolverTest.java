package com.agentsflex.core.prompt;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolGroup;
import com.agentsflex.core.model.chat.tool.ToolGroupMatchers;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class ToolGroupPromptResolverTest {

    @Test
    public void shouldOnlyAttachMatchedGroups() {
        Tool baseTool = tool("base");
        Tool weatherTool = tool("weather");
        Tool databaseTool = tool("database");
        SimplePrompt prompt = new SimplePrompt("please check the weather in Shanghai");
        prompt.setSystemMessage(new SystemMessage("base system prompt"));
        prompt.addTool(baseTool);
        prompt.addToolGroup(ToolGroup.builder("weather")
            .systemPrompt("Use weather tools for live conditions.")
            .addTool(weatherTool)
            .matcher(ToolGroupMatchers.promptContains("weather"))
            .build());
        prompt.addToolGroup(ToolGroup.builder("database")
            .systemPrompt("Use read-only SQL.")
            .addTool(databaseTool)
            .matcher(ToolGroupMatchers.promptContains("database"))
            .build());

        Prompt resolved = ToolGroupPromptResolver.resolve(prompt);

        assertNotSame(prompt, resolved);
        assertEquals(Arrays.asList("base", "weather"),
            Arrays.asList(resolved.getTools().get(0).getName(), resolved.getTools().get(1).getName()));
        assertEquals("base system prompt\n\nUse weather tools for live conditions.",
            resolved.getMessages().get(0).getTextContent());
        assertEquals(1, prompt.getTools().size());
        assertEquals("base system prompt", prompt.getSystemMessage().getContent());
    }

    @Test
    public void shouldKeepAllGroupToolsOutWhenNothingMatches() {
        SimplePrompt prompt = new SimplePrompt("hello");
        prompt.addToolGroup(ToolGroup.builder("weather")
            .systemPrompt("weather instructions")
            .addTool(tool("weather"))
            .matcher(ToolGroupMatchers.promptContains("weather"))
            .build());

        Prompt resolved = ToolGroupPromptResolver.resolve(prompt);

        assertEquals(0, resolved.getTools().size());
        assertEquals(1, resolved.getMessages().size());
        assertSame(prompt.getUserMessage(), resolved.getMessages().get(0));

        BaseChatConfig config = new BaseChatConfig();
        config.setModel("test-model");
        JSONObject body = JSON.parseObject(new OpenAIChatRequestSpecBuilder()
            .buildRequest(resolved, new ChatOptions(), config)
            .getBody());
        assertFalse(body.containsKey("tools"));
        assertEquals(1, body.getJSONArray("messages").size());
    }

    @Test
    public void shouldMatchLatestUserMessageAndNotLeakBetweenTurns() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addToolGroup(ToolGroup.builder("weather")
            .addTool(tool("weather"))
            .matcher(ToolGroupMatchers.promptContains("weather"))
            .build());
        prompt.addMessage(new UserMessage("check weather"));
        assertEquals(1, ToolGroupPromptResolver.resolve(prompt).getTools().size());

        prompt.addMessage(new UserMessage("just say hello"));
        assertEquals(0, ToolGroupPromptResolver.resolve(prompt).getTools().size());
    }

    @Test
    public void shouldAppendMultiplePromptsAndReplaceDuplicateToolNames() {
        Tool first = tool("shared");
        Tool replacement = tool("shared");
        SimplePrompt prompt = new SimplePrompt("anything");
        prompt.addTool(first);
        prompt.addToolGroup(ToolGroup.builder("one")
            .systemPrompt("first instructions")
            .matcher(context -> true)
            .build());
        prompt.addToolGroup(ToolGroup.builder("two")
            .systemPrompt("second instructions")
            .addTool(replacement)
            .matcher(context -> true)
            .build());

        Prompt resolved = ToolGroupPromptResolver.resolve(prompt);
        List<Message> messages = resolved.getMessages();

        assertEquals("first instructions\n\nsecond instructions", messages.get(0).getTextContent());
        assertSame(replacement, resolved.getTools().get(0));
    }

    private static Tool tool(String name) {
        return Tool.builder(name, args -> "ok")
            .description(name + " tool")
            .build();
    }
}
