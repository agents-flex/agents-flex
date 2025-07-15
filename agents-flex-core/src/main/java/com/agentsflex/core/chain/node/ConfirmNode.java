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
        if (confirms != null) {
            for (ConfirmParameter confirm : confirms) {
                confirm.setRefType(RefType.INPUT);
                confirm.setName(confirm.getName());
            }
        }
        this.confirms = confirms;
    }


    @Override
    protected Map<String, Object> execute(Chain chain) {

        List<ConfirmParameter> confirmParameters = new ArrayList<>();
        addConfirmParameter(confirmParameters);

        if (confirms != null) {
            for (ConfirmParameter confirm : confirms) {
                ConfirmParameter clone = confirm.clone();
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
                for (ConfirmParameter confirm : confirms) {
                    Parameter clone = confirm.clone();
                    clone.setName(confirm.getName() + "__" + randomUUID);
                    clone.setRefType(RefType.REF); //固定为 REF
                    newParameters.add(clone);
                }

                Map<String, Object> parameterValues = chain.getParameterValues(this, newParameters, null, true);

                // 设置 inputData，方便前端给用户进行选择
                for (ConfirmParameter confirmParameter : confirmParameters) {
                    if (confirmParameter.getSelectionData() == null) {
                        confirmParameter.setSelectionDataObject(parameterValues.get(confirmParameter.getName()));
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


    private void addConfirmParameter(List<ConfirmParameter> parameters) {
        // “确认 和 取消” 的参数
        ConfirmParameter parameter = new ConfirmParameter();
        parameter.setRefType(RefType.INPUT);
        parameter.setId("confirm");
        parameter.setName("confirm__" + randomUUID);
        parameter.setRequired(true);

        List<Object> selectionData = new ArrayList<>();
        selectionData.add("yes");
        selectionData.add("no");

        parameter.setSelectionData(selectionData);
        parameter.setSelectionDataType("text");
        parameter.setSelectionMode("confirm");
        parameters.add(parameter);
    }


    public static class ConfirmParameter extends Parameter implements Cloneable {

        /**
         * 用户需要确认选择的数据列表，进在 refType 为 INPUT 时有效
         */
        protected List<Object> selectionData;

        /**
         * 用户界面上显示的提示文字，用于引导用户进行选择
         */
        protected String formLabel;

        /**
         * 用户界面上显示的描述文字，用于引导用户进行选择
         */
        protected String formDescription;

        /**
         * 数据类型：文字内容、图片、音频、视频、文件
         */
        protected String selectionDataType;

        /**
         * 用户输入的选择模式，例如："single" 或 "multiple" 或者 “confirm”
         */
        protected String selectionMode;


        public List<Object> getSelectionData() {
            return selectionData;
        }

        public void setSelectionData(List<Object> selectionData) {
            this.selectionData = selectionData;
        }

        public void setSelectionDataObject(Object selectionData) {
            if (selectionData == null) {
                this.selectionData = null;
            } else if (selectionData instanceof Collection) {
                this.selectionData = new ArrayList<>();
                this.selectionData.addAll((Collection<?>) selectionData);
            } else if (selectionData.getClass().isArray()) {
                this.selectionData = new ArrayList<>();
                this.selectionData.addAll(Arrays.asList((Object[]) selectionData));
            } else {
                this.selectionData = new ArrayList<>(1);
                this.selectionData.add(selectionData);
            }
        }

        public String getFormLabel() {
            return formLabel;
        }

        public void setFormLabel(String formLabel) {
            this.formLabel = formLabel;
        }

        public String getFormDescription() {
            return formDescription;
        }

        public void setFormDescription(String formDescription) {
            this.formDescription = formDescription;
        }

        public String getSelectionDataType() {
            return selectionDataType;
        }

        public void setSelectionDataType(String selectionDataType) {
            this.selectionDataType = selectionDataType;
        }

        public String getSelectionMode() {
            return selectionMode;
        }

        public void setSelectionMode(String selectionMode) {
            this.selectionMode = selectionMode;
        }


        @Override
        public String toString() {
            return "ConfirmParameter{" +
                "selectionData=" + selectionData +
                ", formLabel='" + formLabel + '\'' +
                ", formDescription='" + formDescription + '\'' +
                ", selectionDataType='" + selectionDataType + '\'' +
                ", selectionMode='" + selectionMode + '\'' +
                ", id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", dataType=" + dataType +
                ", ref='" + ref + '\'' +
                ", refType=" + refType +
                ", value='" + value + '\'' +
                ", required=" + required +
                ", defaultValue='" + defaultValue + '\'' +
                ", children=" + children +
                '}';
        }

        @Override
        public ConfirmParameter clone() {
            return (ConfirmParameter) super.clone();
        }
    }


}
