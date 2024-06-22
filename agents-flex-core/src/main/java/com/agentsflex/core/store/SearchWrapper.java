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
package com.agentsflex.core.store;

import com.agentsflex.core.store.condition.*;

import java.util.*;
import java.util.function.Consumer;

public class SearchWrapper extends VectorData {

    /**
     * the default value of search data count
     */
    public static final int DEFAULT_MAX_RESULTS = 4;

    /**
     * search text, Vector store will convert the text to vector data
     */
    private String text;

    /**
     * search max result, like the sql "limit" in mysql
     */
    private Integer maxResults = DEFAULT_MAX_RESULTS;

    /**
     * The lowest correlation score, ranging from 0 to 1 (including 0 and 1). Only embeddings with a score of this value or higher will be returned.
     * 0.0 indicates accepting any similarity or disabling similarity threshold filtering. A threshold of 1.0 indicates the need for a perfect match.
     */
    private Double minScore;

    /**
     * The flag of include vector data queries. If the current value is true and the vector content is null,
     * the query text will be automatically converted into vector data through the vector store.
     */
    private boolean withVector = true;

    /**
     * query condition
     */
    private Condition condition;

    /**
     * query fields
     */
    private List<String> outputFields;

    /**
     * whether to output vector data
     */
    private boolean outputVector = false;


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

    public boolean isWithVector() {
        return withVector;
    }

    public void setWithVector(boolean withVector) {
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


    public SearchWrapper in(String key, Collection<?> values) {
        return in(Connector.AND, key, values);
    }

    public SearchWrapper in(Connector connector, String key, Collection<?> values) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.IN, new Key(key), new Value(values.toArray()));
        } else {
            this.condition.connect(new Condition(ConditionType.IN, new Key(key), new Value(values.toArray())), connector);
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

    public SearchWrapper between(String key, Object start, Object end) {
        return between(Connector.AND, key, start, end);
    }

    public SearchWrapper between(Connector connector, String key, Object start, Object end) {
        if (this.condition == null) {
            this.condition = new Condition(ConditionType.BETWEEN, new Key(key), new Value(start, end));
        } else {
            this.condition.connect(new Condition(ConditionType.BETWEEN, new Key(key), new Value(start, end)), connector);
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
     * Convert to expressions for filtering conditions, with different expression requirements for each vendor.
     * Customized adaptor can be achieved through ExpressionAdaptor
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
