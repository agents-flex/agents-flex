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
    private List<Parameter> confirms;

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

    public List<Parameter> getConfirms() {
        return confirms;
    }

    public void setConfirms(List<Parameter> confirms) {
        if (confirms != null) {
            for (Parameter confirm : confirms) {
                confirm.setRefType(RefType.INPUT);
                confirm.setRequired(true); // 必填，才能正确通过 getParameterValuesOnly 获取参数值
                confirm.setName(confirm.getName());
            }
        }
        this.confirms = confirms;
    }


    @Override
    protected Map<String, Object> execute(Chain chain) {

        List<Parameter> confirmParameters = new ArrayList<>();
        addConfirmParameter(confirmParameters);

        if (confirms != null) {
            for (Parameter confirm : confirms) {
                Parameter clone = confirm.clone();
                clone.setName(confirm.getName() + "__" + randomUUID);
                clone.setRefType(RefType.INPUT);
                confirmParameters.add(clone);
            }
        }

        Map<String, Object> values;
        try {
            values = chain.getParameterValues(this, confirmParameters);
        } catch (ChainSuspendException e) {
            chain.setMessage(message);

            if (confirms != null) {
                List<Parameter> newParameters = new ArrayList<>();
                for (Parameter confirm : confirms) {
                    Parameter clone = confirm.clone();
                    clone.setName(confirm.getName() + "__" + randomUUID);
                    clone.setRefType(RefType.REF); // 固定为 REF
                    newParameters.add(clone);
                }

                // 获取参数值，不会触发 ChainSuspendException 错误
                Map<String, Object> parameterValues = chain.getParameterValuesOnly(this, newParameters, null);

                // 设置 enums，方便前端给用户进行选择
                for (Parameter confirmParameter : confirmParameters) {
                    if (confirmParameter.getEnums() == null) {
                        Object enumsObject = parameterValues.get(confirmParameter.getName());
                        confirmParameter.setEnumsObject(enumsObject);
                    }
                }
            }

            throw e;
        }


        Map<String, Object> results = new HashMap<>(values.size());
        values.forEach((key, value) -> {
            int index = key.lastIndexOf("__");
            if (index >= 0) {
                results.put(key.substring(0, index), value);
            } else {
                results.put(key, value);
            }
        });

        return results;
    }


    private void addConfirmParameter(List<Parameter> parameters) {
        // “确认 和 取消” 的参数
        Parameter parameter = new Parameter();
        parameter.setRefType(RefType.INPUT);
        parameter.setId("confirm");
        parameter.setName("confirm__" + randomUUID);
        parameter.setRequired(true);

        List<Object> selectionData = new ArrayList<>();
        selectionData.add("yes");
        selectionData.add("no");

        parameter.setEnums(selectionData);
        parameter.setContentType("text");
        parameter.setFormType("confirm");
        parameters.add(parameter);
    }


}
