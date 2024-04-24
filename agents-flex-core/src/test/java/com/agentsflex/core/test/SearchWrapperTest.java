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
package com.agentsflex.core.test;

import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.condition.Connector;
import org.junit.Assert;
import org.junit.Test;

public class SearchWrapperTest {

    @Test
    public void test01() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("akey", "avalue").eq(Connector.OR, "bkey", "bvalue").group(rw1 -> {
            rw1.eq("ckey", "avalue").in(Connector.AND_NOT, "dkey", "bvalue");
        }).eq("a", "b");

        String expr = "akey = \"avalue\" OR bkey = \"bvalue\" AND (ckey = \"avalue\" AND NOT dkey IN \"bvalue\") AND a = \"b\"";
        Assert.assertEquals(expr, rw.toFilterExpression());

        System.out.println(rw.toFilterExpression());
    }
}
