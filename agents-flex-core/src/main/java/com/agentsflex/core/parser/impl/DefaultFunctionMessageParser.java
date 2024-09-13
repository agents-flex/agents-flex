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
package com.agentsflex.core.parser.impl;

import com.agentsflex.core.message.FunctionMessage;
import com.agentsflex.core.parser.Parser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.Map;

public class DefaultFunctionMessageParser implements FunctionMessageParser {

    private String functionNamePath;
    private String functionArgsPath;
    private Parser<String, Map<String, Object>> functionArgsParser;

    public String getFunctionNamePath() {
        return functionNamePath;
    }

    public void setFunctionNamePath(String functionNamePath) {
        this.functionNamePath = functionNamePath;
    }

    public String getFunctionArgsPath() {
        return functionArgsPath;
    }

    public void setFunctionArgsPath(String functionArgsPath) {
        this.functionArgsPath = functionArgsPath;
    }

    public Parser<String, Map<String, Object>> getFunctionArgsParser() {
        return functionArgsParser;
    }

    public void setFunctionArgsParser(Parser<String, Map<String, Object>> functionArgsParser) {
        this.functionArgsParser = functionArgsParser;
    }

    @Override
    public FunctionMessage parse(JSONObject jsonObject) {
        String functionName = (String) JSONPath.eval(jsonObject, this.functionNamePath);
        if (StringUtil.noText(functionName)) {
            return null;
        }
        FunctionMessage functionMessage = new FunctionMessage();
        functionMessage.setFunctionName(functionName);
        Object argsResult = JSONPath.eval(jsonObject, this.functionArgsPath);
        if (argsResult instanceof String && this.functionArgsParser != null) {
            functionMessage.setArgs(this.functionArgsParser.parse((String) argsResult));
        } else if (argsResult instanceof Map) {
            functionMessage.setArgs((Map) argsResult);
        }
        return functionMessage;
    }
}
