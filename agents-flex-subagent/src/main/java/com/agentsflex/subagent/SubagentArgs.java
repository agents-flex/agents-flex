/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.subagent;

import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

public class SubagentArgs {

    @ToolParam(name = "name", description = "The name of the agent to use", required = true)
    private String name;

    @ToolParam(name = "description", description = "A short (3-5 word) description of the task", required = true)
    private String description;

    @ToolParam(name = "prompt", description = "The task for the agent to perform", required = true)
    private String prompt;

    @ToolParam(name = "resume", description = "Optional agent ID to resume from. If provided, the agent will continue from the previous execution transcript.", required = false)
    private String resume;

    @ToolParam(name = "run_in_background", description = "Set to true to run this agent in the background. Use TaskOutput to read the output later.", required = false)
    private Boolean run_in_background;


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getResume() {
        return resume;
    }

    public void setResume(String resume) {
        this.resume = resume;
    }

    public Boolean getRun_in_background() {
        return run_in_background;
    }

    public void setRun_in_background(Boolean run_in_background) {
        this.run_in_background = run_in_background;
    }
}
