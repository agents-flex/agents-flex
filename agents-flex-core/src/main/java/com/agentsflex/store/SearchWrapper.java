/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.store;

import com.agentsflex.store.condition.*;

import java.util.*;
import java.util.function.Consumer;

public class SearchWrapper extends VectorData {

    /**
     * 集合名称（表名？）
     */
    private String collectionName;

    /**
     * 分区名称
     */
    private String partitionName;

    /**
     * 默认返回的数据量
     */
    public static final int DEFAULT_MAX_RESULTS = 4;

    /**
     * 搜索的内容，一般情况下，会把 text 转换为向量数据后再进行搜索
     */
    private String text;

    /**
     * 返回的最大数据量，类似传统数据库 mysql 的 limit
     */
    private Integer maxResults = DEFAULT_MAX_RESULTS;

    /**
     * 最低相关性得分，范围从 0 到 1（包括 0 到 1 ）。只有分数为该值或更高的嵌入才会返回。
     * 0.0 表示接受任何相似性或禁用相似性阈值筛选。阈值 1.0 表示需要完全匹配。
     */
    private Double minScore;

    /**
     * 是否包含向量数据查询，如果当前值为 true，且向量内容为 null 时，会自动通过向量数据库把 text 转换为 向量数据
     */
    private Boolean withVector;

    /**
     * 查询条件
     */
    private Condition condition;

    /**
     * 查询的列名
     */
    private List<String> outputFields;

    /**
     * 是否输出向量数据
     */
    private boolean outputVector = false;

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public SearchWrapper collectionName(String collectionName) {
        setCollectionName(collectionName);
        return this;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public void setPartitionName(String partitionName) {
        this.partitionName = partitionName;
    }

    public SearchWrapper partitionName(String partitionName) {
        setPartitionName(partitionName);
        return this;
    }


    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public SearchWrapper text(String text) {
        setText(text);
        return this;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public SearchWrapper maxResults(Integer maxResults) {
        setMaxResults(maxResults);
        return this;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    public SearchWrapper minScore(Double minScore) {
        setMinScore(minScore);
        return this;
    }

    public Boolean getWithVector() {
        return withVector;
    }

    public void setWithVector(Boolean withVector) {
        this.withVector = withVector;
    }

    public SearchWrapper withVector(Boolean withVector) {
        setWithVector(withVector);
        return this;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    public List<String> getOutputFields() {
        return outputFields;
    }

    public void setOutputFields(List<String> outputFields) {
        this.outputFields = outputFields;
    }

    public SearchWrapper outputFields(Collection<String> outputFields) {
        setOutputFields(new ArrayList<>(outputFields));
        return this;
    }

    public SearchWrapper outputFields(String... outputFields) {
        setOutputFields(Arrays.asList(outputFields));
        return this;
    }

    public boolean isOutputVector() {
        return outputVector;
    }

    public void setOutputVector(boolean outputVector) {
        this.outputVector = outputVector;
    }

    public SearchWrapper outputVector(boolean outputVector) {
        setOutputVector(outputVector);
        return this;
    }


    public SearchWrapper eq(String key, Object value) {
        return eq(Connector.AND, key, value);
    }

    public SearchWrapper eq(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.EQ, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.EQ, new Key(key), new Value(value)), connector);
        }
        return this;
    }

    public SearchWrapper ne(String key, Object value) {
        return ne(Connector.AND, key, value);
    }

    public SearchWrapper ne(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.NE, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.NE, new Key(key), new Value(value)), connector);
        }
        return this;
    }

    public SearchWrapper gt(String key, Object value) {
        return gt(Connector.AND, key, value);
    }

    public SearchWrapper gt(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.GT, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.GT, new Key(key), new Value(value)), connector);
        }
        return this;
    }


    public SearchWrapper ge(String key, Object value) {
        return ge(Connector.AND, key, value);
    }

    public SearchWrapper ge(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.GE, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.GE, new Key(key), new Value(value)), connector);
        }
        return this;
    }


    public SearchWrapper lt(String key, Object value) {
        return lt(Connector.AND, key, value);
    }

    public SearchWrapper lt(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.LT, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.LT, new Key(key), new Value(value)), connector);
        }
        return this;
    }


    public SearchWrapper le(String key, Object value) {
        return le(Connector.AND, key, value);
    }

    public SearchWrapper le(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.LE, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.LE, new Key(key), new Value(value)), connector);
        }
        return this;
    }


    public SearchWrapper in(String key, Object value) {
        return in(Connector.AND, key, value);
    }

    public SearchWrapper in(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.IN, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.IN, new Key(key), new Value(value)), connector);
        }
        return this;
    }

    public SearchWrapper min(String key, Object value) {
        return min(Connector.AND, key, value);
    }

    public SearchWrapper min(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.NIN, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.NIN, new Key(key), new Value(value)), connector);
        }
        return this;
    }

    public SearchWrapper between(String key, Object value) {
        return between(Connector.AND, key, value);
    }

    public SearchWrapper between(Connector connector, String key, Object value) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.BETWEEN, new Key(key), new Value(value));
        } else {
            this.condition.connect(new Condition(ConditionType.BETWEEN, new Key(key), new Value(value)), connector);
        }
        return this;
    }


    public SearchWrapper group(SearchWrapper wrapper) {
        return group(wrapper.condition);
    }

    public SearchWrapper group(Condition condition) {
        if (this.condition == null) {
            this.condition = new Group(condition);
        } else {
            this.condition.connect(new Group(condition), Connector.AND);
        }
        return this;
    }

    public SearchWrapper group(Consumer<SearchWrapper> consumer) {
        SearchWrapper newWrapper = new SearchWrapper();
        consumer.accept(newWrapper);
        Condition condition = newWrapper.condition;
        if (condition != null) {
            group(condition);
        }
        return this;
    }


    /**
     * 转换为过滤条件的表达式，每个厂商的表达式要求不一样，可以通过 ExpressionAdaptor 来实现自定义的适配
     *
     * @return 过滤条件表达式
     */
    public String toFilterExpression() {
        return toFilterExpression(ExpressionAdaptor.DEFAULT);
    }

    public String toFilterExpression(ExpressionAdaptor adaptor) {
        if (this.condition == null) {
            return null;
        } else {
            Objects.requireNonNull(adaptor, "adaptor must not be null");
            return this.condition.toExpression(adaptor);
        }
    }

}
