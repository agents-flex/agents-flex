package com.agentsflex.tool.commons;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;

import java.util.ArrayList;
import java.util.List;

public class CommonTools {

    public static List<Tool> getAllCommonsTools() {
        List<Tool> tools = new ArrayList<>();
        tools.addAll(ToolScanner.scan(GrepTool.builder().build()));
        tools.addAll(ToolScanner.scan(GlobTool.builder().build()));
        tools.addAll(ToolScanner.scan(FileSystemTools.builder().build()));
        tools.addAll(ToolScanner.scan(ShellTools.builder().build()));
        return tools;
    }
}
