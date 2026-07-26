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
package com.agentsflex.store.qdrant;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import io.qdrant.client.grpc.Points;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QdrantConditionBuilderTest {

    private final QdrantConditionBuilder builder = new QdrantConditionBuilder();

    @Test
    public void shouldBuildInNinAndBetween() {
        SearchWrapper wrapper = new SearchWrapper()
            .in("category", Arrays.asList("AI", "Java"))
            .nin("status", Arrays.asList("hidden", "deleted"))
            .between("views", 5, 15);

        Points.Filter filter = builder.build(wrapper.getCondition());

        assertEquals(2, filter.getMustCount());
        assertEquals(1, filter.getMustNotCount());
        assertEquals(2, filter.getMust(0).getFilter().getShouldCount());
        assertTrue(filter.getMust(1).getField().getRange().hasGte());
        assertTrue(filter.getMust(1).getField().getRange().hasLte());
    }

    @Test
    public void shouldPreserveOrAndPrecedence() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("a", 1)
            .eq(Connector.OR, "b", 2)
            .gt("c", 3);

        Points.Filter filter = builder.build(wrapper.getCondition());

        assertEquals(2, filter.getShouldCount());
        assertEquals(2, filter.getShould(1).getFilter().getMustCount());
    }

    @Test
    public void shouldBuildNullChecksAndUnaryNot() {
        SearchWrapper wrapper = new SearchWrapper()
            .isNull("optional")
            .isNotNull("required")
            .not(group -> group.eq("status", "deleted"));

        Points.Filter filter = builder.build(wrapper.getCondition());

        assertEquals(1, filter.getMustCount());
        assertEquals(2, filter.getMustNotCount());
        assertTrue(filter.getMust(0).getIsNull().getKey().contains("optional"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidRangeValues() {
        SearchWrapper wrapper = new SearchWrapper().gt("views", "ten");
        builder.build(wrapper.getCondition());
    }
}
