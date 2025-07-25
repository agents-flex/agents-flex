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
package com.agentsflex.core.react;

public class ReActStep {

    private String thought;
    private String action;
    private String actionInput;

    public ReActStep() {
    }

    public ReActStep(String thought, String action, String actionInput) {
        this.thought = thought;
        this.action = action;
        this.actionInput = actionInput;
    }

    public String getThought() {
        return thought;
    }

    public void setThought(String thought) {
        this.thought = thought;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActionInput() {
        return actionInput;
    }

    public void setActionInput(String actionInput) {
        this.actionInput = actionInput;
    }

    @Override
    public String toString() {
        return "ReActStep{" +
            "thought='" + thought + '\'' +
            ", action='" + action + '\'' +
            ", actionInput='" + actionInput + '\'' +
            '}';
    }
}
