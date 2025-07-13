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
package com.agentsflex.core.chain.node;

import com.agentsflex.core.chain.Chain;
import com.agentsflex.core.chain.ChainSuspendException;
import com.agentsflex.core.chain.Parameter;
import com.agentsflex.core.chain.RefType;

import java.util.*;

public class ConfirmNode extends BaseNode {

    private String randomUUID;
    private String message;
    private List<ConfirmParameter> confirms;

    public ConfirmNode() {
        this.randomUUID = UUID.randomUUID().toString();
    }

    public String getRandomUUID() {
        return randomUUID;
    }

    public void setRandomUUID(String randomUUID) {
        this.randomUUID = randomUUID;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ConfirmParameter> getConfirms() {
        return confirms;
    }

    public void setConfirms(List<ConfirmParameter> confirms) {
        this.confirms = confirms;
    }


    @Override
    protected Map<String, Object> execute(Chain chain) {
        // “确认 和 取消” 的参数
        ConfirmParameter parameter = new ConfirmParameter();
        parameter.setRefType(RefType.INPUT);
        parameter.setId("confirm");
        parameter.setName("confirm__" + randomUUID);
        parameter.setRequired(true);

        List<Object> inputData = new ArrayList<>();
        inputData.add("confirm");
        inputData.add("cancel");

        parameter.setInputData(inputData);
        parameter.setInputDataType("text");
        parameter.setInputActionType("confirm_cancel");

        List<Parameter> parameters = new ArrayList<>();
        parameters.add(parameter);

        if (confirms != null) {
            for (ConfirmParameter confirm : confirms) {
                confirm.setRefType(RefType.INPUT);
                confirm.setName(confirm.getName() + "__" + randomUUID);
                parameters.add(confirm);
            }
        }

        Map<String, Object> values;
        try {
            values = chain.getParameterValues(this, parameters);
        } catch (ChainSuspendException e) {
            chain.setMessage(message);
            throw e;
        }

        Map<String, Object> results = new HashMap<>(values.size());
        values.forEach((key, value) -> {
            int index = key.indexOf("__");
            if (index >= 0) {
                results.put(key.substring(0, index), value);
            } else {
                results.put(key, value);
            }
        });

        return results;
    }


    public static class ConfirmParameter extends Parameter {

        /**
         * 输入数据，进在 refType 为 INPUT 时有效
         */
        protected List<Object> inputData;

        /**
         * 数据类型：文字内容、图片、音频、视频、文件
         */
        protected String inputDataType;

        /**
         * 用户输入的选择模式，例如："single" 或 "multiple" 或者 “confirm_cancel”
         */
        protected String inputActionType;

        public List<Object> getInputData() {
            return inputData;
        }

        public void setInputData(List<Object> inputData) {
            this.inputData = inputData;
        }

        public String getInputDataType() {
            return inputDataType;
        }

        public void setInputDataType(String inputDataType) {
            this.inputDataType = inputDataType;
        }

        public String getInputActionType() {
            return inputActionType;
        }

        public void setInputActionType(String inputActionType) {
            this.inputActionType = inputActionType;
        }
    }


}
