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

import java.util.Objects;
import java.util.function.Consumer;

public class SearchWrapper extends VectorData {

    /**
     * 返回的最大数据量，类似传统数据库 mysql 的 limit
     */
    private Integer maxResults;

    /**
     * 最低相关性得分，范围从 0 到 1（包括 0 到 1 ）。只有分数为该值或更高的嵌入才会返回。
     */
    private Double minScore;

    /**
     * 是否包含向量数据查询
     */
    private Boolean withVector;

    /**
     * 查询条件
     */
    private Condition condition;

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    public Boolean getWithVector() {
        return withVector;
    }

    public void setWithVector(Boolean withVector) {
        this.withVector = withVector;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
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

    public String toExpression() {
        return toExpression(ExpressionAdaptor.DEFAULT);
    }

    public String toExpression(ExpressionAdaptor adaptor) {
        if (this.condition == null) {
            return null;
        } else {
            Objects.requireNonNull(adaptor, "adaptor must not be null");
            return this.condition.toExpression(adaptor);
        }
    }

}
