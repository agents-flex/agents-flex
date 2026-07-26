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
package com.agentsflex.core.store;

import com.agentsflex.core.store.condition.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * 向量检索参数构造器。
 *
 * <p>既可以通过 {@link #text(String)} 提供待向量化的文本，也可以通过继承自
 * {@link VectorData} 的 {@code setVector} 直接提供查询向量。过滤条件支持链式 API、
 * {@link Condition} 对象以及 SQL 风格字符串三种构造方式。</p>
 *
 * <p>本类只描述查询，不负责执行查询。不同向量数据库如何渲染过滤表达式，
 * 由对应的 {@link ExpressionAdaptor} 决定。</p>
 */
public class SearchWrapper extends VectorData {

    /**
     * 默认返回结果数。
     */
    public static final int DEFAULT_MAX_RESULTS = 4;

    /**
     * 待检索文本。配置了嵌入模型且未直接提供查询向量时，存储实现可将其转换为向量。
     */
    private String text;

    /**
     * 最大返回结果数，作用类似 SQL 的 {@code LIMIT}。
     */
    private Integer maxResults = DEFAULT_MAX_RESULTS;

    /**
     * 最低相似度阈值，通常取值为 0 到 1（含边界）。具体分值含义以存储实现为准。
     */
    private Double minScore;

    /**
     * 是否执行向量检索。为 {@code true} 且查询向量为空时，文档存储可自动向量化查询文本。
     */
    private boolean withVector = true;

    /**
     * 元数据过滤条件树。
     */
    private Condition condition;

    /**
     * 希望存储端返回的字段；为空时由具体存储实现决定返回范围。
     */
    private List<String> outputFields;

    /**
     * 是否在查询结果中返回原始向量。
     */
    private boolean outputVector = false;

    public SearchWrapper() {
    }

    /**
     * 创建查询请求的独立副本。向量、条件树、返回字段和元数据容器均会复制。
     */
    public SearchWrapper(SearchWrapper source) {
        Objects.requireNonNull(source, "source must not be null");
        this.text = source.text;
        this.maxResults = source.maxResults;
        this.minScore = source.minScore;
        this.withVector = source.withVector;
        this.condition = Condition.copyOf(source.condition);
        this.outputFields = source.outputFields == null
            ? null : Collections.unmodifiableList(new ArrayList<>(source.outputFields));
        this.outputVector = source.outputVector;
        this.vector = source.vector == null ? null : source.vector.clone();
        this.score = source.score;
        setMetadataMap(source.getMetadataMap());
    }

    /** 返回查询请求的独立副本。 */
    public static SearchWrapper from(SearchWrapper source) {
        return new SearchWrapper(source);
    }

    /** 返回查询请求的独立副本。 */
    public SearchWrapper copy() {
        return new SearchWrapper(this);
    }

    /**
     * 校验所有通用查询参数。具体 Store 仍可补充向量维度等服务端约束。
     *
     * @return 当前查询对象
     */
    public SearchWrapper validate() {
        validateMaxResults(maxResults);
        validateMinScore(minScore);
        validateOutputFields(outputFields);
        return this;
    }

    @Override
    public float[] getVector() {
        return vector == null ? null : vector.clone();
    }

    @Override
    public void setVector(float[] vector) {
        this.vector = vector == null ? null : vector.clone();
    }


    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /**
     * 设置待检索文本。
     *
     * @return 当前构造器
     */
    public SearchWrapper text(String text) {
        setText(text);
        return this;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        validateMaxResults(maxResults);
        this.maxResults = maxResults;
    }

    /**
     * 设置最大返回结果数。
     *
     * @return 当前构造器
     */
    public SearchWrapper maxResults(Integer maxResults) {
        setMaxResults(maxResults);
        return this;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        validateMinScore(minScore);
        this.minScore = minScore;
    }

    /**
     * 设置最低相似度阈值。
     *
     * @return 当前构造器
     */
    public SearchWrapper minScore(Double minScore) {
        setMinScore(minScore);
        return this;
    }

    public boolean isWithVector() {
        return withVector;
    }

    public void setWithVector(boolean withVector) {
        this.withVector = withVector;
    }

    /**
     * 设置是否执行向量检索。关闭后可以只使用 {@link #condition} 做标量过滤。
     *
     * @return 当前构造器
     */
    public SearchWrapper withVector(Boolean withVector) {
        setWithVector(Objects.requireNonNull(withVector, "withVector must not be null"));
        return this;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = Condition.copyOf(condition);
    }

    /**
     * 解析 SQL 风格表达式并替换当前全部过滤条件。
     *
     * @param expression 条件表达式
     * @throws IllegalArgumentException 表达式为空或语法不合法时抛出
     */
    public void setConditionExpression(String expression) {
        this.condition = ConditionExpressionParser.parse(expression);
    }

    /**
     * 解析 SQL 风格表达式，并使用 {@link Connector#AND} 追加到已有条件。
     * 当当前条件为空时，解析结果直接成为根条件。
     *
     * @param expression 条件表达式
     * @return 当前构造器
     * @throws IllegalArgumentException 表达式为空或语法不合法时抛出
     */
    public SearchWrapper condition(String expression) {
        return condition(Connector.AND, expression);
    }

    /**
     * 解析 SQL 风格表达式，并使用指定连接符追加到已有条件。
     * 追加的表达式会被分组，避免其内部的 AND/OR 优先级影响外部条件。
     *
     * @param connector 与已有条件连接的逻辑连接符
     * @param expression 条件表达式
     * @return 当前构造器
     * @throws NullPointerException 连接符为 {@code null} 时抛出
     * @throws IllegalArgumentException 表达式为空或语法不合法时抛出
     */
    public SearchWrapper condition(Connector connector, String expression) {
        Objects.requireNonNull(connector, "connector must not be null");
        Condition parsed = ConditionExpressionParser.parse(expression);
        if (this.condition == null) {
            this.condition = parsed;
        } else {
            this.condition.connect(new Group(parsed), connector);
        }
        return this;
    }

    public List<String> getOutputFields() {
        return outputFields;
    }

    public void setOutputFields(List<String> outputFields) {
        validateOutputFields(outputFields);
        this.outputFields = outputFields == null
            ? null : Collections.unmodifiableList(new ArrayList<>(outputFields));
    }

    /** 设置需要返回的字段，并复制传入集合。 */
    public SearchWrapper outputFields(Collection<String> outputFields) {
        Objects.requireNonNull(outputFields, "outputFields must not be null");
        setOutputFields(new ArrayList<>(outputFields));
        return this;
    }

    /** 设置需要返回的字段。 */
    public SearchWrapper outputFields(String... outputFields) {
        Objects.requireNonNull(outputFields, "outputFields must not be null");
        setOutputFields(Arrays.asList(outputFields));
        return this;
    }

    public boolean isOutputVector() {
        return outputVector;
    }

    public void setOutputVector(boolean outputVector) {
        this.outputVector = outputVector;
    }

    /** 设置是否在结果中返回向量数据。 */
    public SearchWrapper outputVector(boolean outputVector) {
        setOutputVector(outputVector);
        return this;
    }


    /** 使用 AND 追加等于条件。 */
    public SearchWrapper eq(String key, Object value) {
        return eq(Connector.AND, key, value);
    }

    /** 使用指定连接符追加等于条件。 */
    public SearchWrapper eq(Connector connector, String key, Object value) {
        return appendCondition(connector, condition(ConditionType.EQ, key, value));
    }

    /** 使用 AND 追加不等于条件。 */
    public SearchWrapper ne(String key, Object value) {
        return ne(Connector.AND, key, value);
    }

    /** 使用指定连接符追加不等于条件。 */
    public SearchWrapper ne(Connector connector, String key, Object value) {
        return appendCondition(connector, condition(ConditionType.NE, key, value));
    }

    /** 使用 AND 追加大于条件。 */
    public SearchWrapper gt(String key, Object value) {
        return gt(Connector.AND, key, value);
    }

    /** 使用指定连接符追加大于条件。 */
    public SearchWrapper gt(Connector connector, String key, Object value) {
        return appendCondition(connector, orderedCondition(ConditionType.GT, key, value));
    }


    /** 使用 AND 追加大于等于条件。 */
    public SearchWrapper ge(String key, Object value) {
        return ge(Connector.AND, key, value);
    }

    /** 使用指定连接符追加大于等于条件。 */
    public SearchWrapper ge(Connector connector, String key, Object value) {
        return appendCondition(connector, orderedCondition(ConditionType.GE, key, value));
    }


    /** 使用 AND 追加小于条件。 */
    public SearchWrapper lt(String key, Object value) {
        return lt(Connector.AND, key, value);
    }

    /** 使用指定连接符追加小于条件。 */
    public SearchWrapper lt(Connector connector, String key, Object value) {
        return appendCondition(connector, orderedCondition(ConditionType.LT, key, value));
    }


    /** 使用 AND 追加小于等于条件。 */
    public SearchWrapper le(String key, Object value) {
        return le(Connector.AND, key, value);
    }

    /** 使用指定连接符追加小于等于条件。 */
    public SearchWrapper le(Connector connector, String key, Object value) {
        return appendCondition(connector, orderedCondition(ConditionType.LE, key, value));
    }


    /** 使用 AND 追加 IN 条件。 */
    public SearchWrapper in(String key, Collection<?> values) {
        return in(Connector.AND, key, values);
    }

    /** 使用指定连接符追加 IN 条件。 */
    public SearchWrapper in(Connector connector, String key, Collection<?> values) {
        return appendCondition(connector, multiValueCondition(ConditionType.IN, key, values));
    }

    /** 使用 AND 追加 NOT IN 条件。 */
    public SearchWrapper nin(String key, Collection<?> values) {
        return nin(Connector.AND, key, values);
    }

    /** 使用指定连接符追加 NOT IN 条件。 */
    public SearchWrapper nin(Connector connector, String key, Collection<?> values) {
        return appendCondition(connector, multiValueCondition(ConditionType.NIN, key, values));
    }

    /** 使用 AND 追加 BETWEEN 条件，边界由存储实现解释。 */
    public SearchWrapper between(String key, Object start, Object end) {
        return between(Connector.AND, key, start, end);
    }

    /** 使用指定连接符追加 BETWEEN 条件。 */
    public SearchWrapper between(Connector connector, String key, Object start, Object end) {
        requireKey(key);
        Objects.requireNonNull(start, "BETWEEN start must not be null");
        Objects.requireNonNull(end, "BETWEEN end must not be null");
        return appendCondition(connector,
            new Condition(ConditionType.BETWEEN, new Key(key), new Value(start, end)));
    }

    /** 使用 AND 追加 IS NULL 条件。 */
    public SearchWrapper isNull(String key) {
        return isNull(Connector.AND, key);
    }

    /** 使用指定连接符追加 IS NULL 条件。 */
    public SearchWrapper isNull(Connector connector, String key) {
        return appendCondition(connector, condition(ConditionType.IS_NULL, key, null));
    }

    /** 使用 AND 追加 IS NOT NULL 条件。 */
    public SearchWrapper isNotNull(String key) {
        return isNotNull(Connector.AND, key);
    }

    /** 使用指定连接符追加 IS NOT NULL 条件。 */
    public SearchWrapper isNotNull(Connector connector, String key) {
        return appendCondition(connector, condition(ConditionType.IS_NOT_NULL, key, null));
    }


    /** 使用 AND 追加另一个查询构造器中的条件，并将其作为一个分组。 */
    public SearchWrapper group(SearchWrapper wrapper) {
        Objects.requireNonNull(wrapper, "wrapper must not be null");
        return group(wrapper.condition);
    }

    /** 使用 AND 追加条件分组。 */
    public SearchWrapper group(Condition condition) {
        Objects.requireNonNull(condition, "condition must not be null");
        return appendCondition(Connector.AND, new Group(Condition.copyOf(condition)));
    }

    /**
     * 使用独立的临时构造器创建条件分组，并通过 AND 追加。
     * 临时构造器没有条件时不会追加空分组。
     */
    public SearchWrapper group(Consumer<SearchWrapper> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        SearchWrapper newWrapper = new SearchWrapper();
        consumer.accept(newWrapper);
        Condition condition = newWrapper.condition;
        if (condition != null) {
            group(condition);
        }
        return this;
    }

    /** {@link #group(Consumer)} 的语义化别名。 */
    public SearchWrapper andCriteria(Consumer<SearchWrapper> consumer) {
        return group(consumer);
    }

    /** 使用独立的临时构造器创建条件分组，并通过 OR 追加。 */
    public SearchWrapper orCriteria(Consumer<SearchWrapper> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        SearchWrapper newWrapper = new SearchWrapper();
        consumer.accept(newWrapper);
        Condition condition = newWrapper.condition;
        if (condition != null) {
            if (this.condition == null) {
                this.condition = new Group(condition);
            } else {
                this.condition.connect(new Group(condition), Connector.OR);
            }
        }
        return this;
    }

    /** 使用 AND 追加对给定条件分组的一元 NOT。 */
    public SearchWrapper not(Condition condition) {
        Objects.requireNonNull(condition, "condition must not be null");
        return appendCondition(Connector.AND, new Not(Condition.copyOf(condition)));
    }

    /** 使用 AND 追加对另一个查询构造器条件的一元 NOT。 */
    public SearchWrapper not(SearchWrapper wrapper) {
        Objects.requireNonNull(wrapper, "wrapper must not be null");
        return not(wrapper.condition);
    }

    /** 使用临时构造器创建条件分组，并通过 AND 追加一元 NOT。 */
    public SearchWrapper not(Consumer<SearchWrapper> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        SearchWrapper nested = new SearchWrapper();
        consumer.accept(nested);
        if (nested.condition != null) {
            not(nested.condition);
        }
        return this;
    }

    /**
     * 使用默认适配器将条件树转换为过滤表达式。
     *
     * @return 过滤表达式；没有条件时返回 {@code null}
     */
    public String toFilterExpression() {
        return toFilterExpression(ExpressionAdaptor.DEFAULT);
    }

    /**
     * 使用指定适配器将条件树转换为存储端可识别的过滤表达式。
     *
     * @param adaptor 表达式适配器
     * @return 过滤表达式；没有条件时返回 {@code null}
     */
    public String toFilterExpression(ExpressionAdaptor adaptor) {
        if (this.condition == null) {
            return null;
        } else {
            Objects.requireNonNull(adaptor, "adaptor must not be null");
            return this.condition.toExpression(adaptor);
        }
    }

    private SearchWrapper appendCondition(Connector connector, Condition nextCondition) {
        Objects.requireNonNull(connector, "connector must not be null");
        if (this.condition == null) {
            nextCondition.setConnector(null);
            this.condition = nextCondition;
        } else {
            this.condition.connect(nextCondition, connector);
        }
        return this;
    }

    private Condition condition(ConditionType type, String key, Object value) {
        requireKey(key);
        return new Condition(type, new Key(key), new Value(value));
    }

    private Condition orderedCondition(ConditionType type, String key, Object value) {
        Objects.requireNonNull(value, type + " value must not be null");
        return condition(type, key, value);
    }

    private Condition multiValueCondition(ConditionType type, String key, Collection<?> values) {
        requireKey(key);
        Objects.requireNonNull(values, type + " values must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException(type + " values must not be empty");
        }
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(type + " values must not contain null");
            }
        }
        return new Condition(type, new Key(key), new Value(values.toArray()));
    }

    private static void requireKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("condition key must not be blank");
        }
    }

    private static void validateMaxResults(Integer maxResults) {
        if (maxResults == null || maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be greater than 0");
        }
    }

    private static void validateMinScore(Double minScore) {
        if (minScore != null && (minScore.isNaN() || minScore.isInfinite()
            || minScore < 0d || minScore > 1d)) {
            throw new IllegalArgumentException("minScore must be in the [0, 1] range");
        }
    }

    private static void validateOutputFields(Collection<String> outputFields) {
        if (outputFields == null) {
            return;
        }
        for (String field : outputFields) {
            if (field == null || field.trim().isEmpty()) {
                throw new IllegalArgumentException("outputFields must not contain blank fields");
            }
        }
    }

}
