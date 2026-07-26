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
package com.agentsflex.core.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import org.junit.Assert;
import org.junit.Test;

public class SearchWrapperTest {

    @Test
    public void test01() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("akey", "avalue").eq(Connector.OR, "bkey", "bvalue").group(rw1 -> {
            rw1.eq("ckey", "avalue").in(Connector.AND_NOT, "dkey", Arrays.asList("aa", "bb"));
        }).eq("a", "b");

        String expr = "akey = \"avalue\" OR bkey = \"bvalue\" AND (ckey = \"avalue\" AND NOT dkey IN (\"aa\",\"bb\")) AND a = \"b\"";
        Assert.assertEquals(expr, rw.toFilterExpression());

        System.out.println(rw.toFilterExpression());
    }

    @Test
    public void test02() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("akey", "avalue").between(Connector.OR, "bkey", "1", "100").in("ckey", Arrays.asList("aa", "bb"));

        String expr = "akey = \"avalue\" OR bkey BETWEEN \"1\" AND \"100\" AND ckey IN (\"aa\",\"bb\")";
        Assert.assertEquals(expr, rw.toFilterExpression());

        System.out.println(rw.toFilterExpression());
    }

    @Test
    public void test03() {
        SearchWrapper rw = new SearchWrapper();
        rw.eq("ak", "av")
            // and ( 子条件 )
            .andCriteria(rw1 -> {
                rw1.eq("bk", "bv").in("x1", Arrays.asList("1", "2"));
            })
            // or ( 子条件 )
            .orCriteria(rw1 -> {
                rw1.eq("ck", "cv").eq("ck1", "cv1");
            })
            .eq("a", "b");

        String expr = "ak = \"av\" AND (bk = \"bv\" AND x1 IN (\"1\",\"2\")) OR (ck = \"cv\" AND ck1 = \"cv1\") AND a = \"b\"";
        Assert.assertEquals(expr, rw.toFilterExpression());

        System.out.println(rw.toFilterExpression());
    }

    @Test
    public void testConditionExpression() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("tenant", "agents-flex")
            .condition("views >= 10 OR category NOT IN ('hidden', 'deleted')");

        Assert.assertEquals(
            "tenant = \"agents-flex\" AND (views >= \"10\" OR category NOT IN (\"hidden\",\"deleted\"))",
            wrapper.toFilterExpression());

        wrapper.setConditionExpression("enabled = true");
        Assert.assertEquals("enabled = \"true\"", wrapper.toFilterExpression());
    }

    @Test
    public void shouldValidateCommonParametersAtAssignmentTime() {
        assertInvalid(() -> new SearchWrapper().maxResults(0), "maxResults");
        assertInvalid(() -> new SearchWrapper().maxResults(null), "maxResults");
        assertInvalid(() -> new SearchWrapper().minScore(-0.1), "minScore");
        assertInvalid(() -> new SearchWrapper().minScore(1.1), "minScore");
        assertInvalid(() -> new SearchWrapper().minScore(Double.NaN), "minScore");
        assertInvalid(() -> new SearchWrapper().withVector(null), "withVector");
        assertInvalid(() -> new SearchWrapper().eq(" ", 1), "key");
        assertInvalid(() -> new SearchWrapper().gt("age", null), "value");
        assertInvalid(() -> new SearchWrapper().in("status", Arrays.asList()), "empty");
        assertInvalid(() -> new SearchWrapper().in("status", Arrays.asList("ok", null)), "null");
        assertInvalid(() -> new SearchWrapper().between("age", null, 10), "start");
        assertInvalid(() -> new SearchWrapper().outputFields("id", " "), "outputFields");
    }

    @Test
    public void shouldDefensivelyCopyMutableInputsAndOutputs() {
        List<String> fields = new ArrayList<>(Arrays.asList("id", "title"));
        float[] vector = {1f, 2f};
        SearchWrapper wrapper = new SearchWrapper().outputFields(fields);
        wrapper.setVector(vector);

        fields.add("content");
        vector[0] = 9f;
        Assert.assertEquals(Arrays.asList("id", "title"), wrapper.getOutputFields());
        Assert.assertArrayEquals(new float[]{1f, 2f}, wrapper.getVector(), 0f);

        float[] returnedVector = wrapper.getVector();
        returnedVector[0] = 8f;
        Assert.assertArrayEquals(new float[]{1f, 2f}, wrapper.getVector(), 0f);
        try {
            wrapper.getOutputFields().add("content");
            Assert.fail("Expected outputFields to be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void shouldCreateIndependentCopies() {
        SearchWrapper original = new SearchWrapper()
            .text("first")
            .maxResults(8)
            .minScore(0.7)
            .outputFields("id", "title")
            .eq("tenant", "a")
            .orCriteria(group -> group.in("status", Arrays.asList("ready", "pending")));
        original.setVector(new float[]{1f, 2f});
        original.putMetadata("traceId", "trace-1");

        SearchWrapper copied = SearchWrapper.from(original);
        original.text("second").eq("newField", true);
        original.setVector(new float[]{9f, 9f});
        original.setOutputFields(Arrays.asList("content"));
        original.putMetadata("traceId", "trace-2");

        Assert.assertEquals("first", copied.getText());
        Assert.assertEquals(Integer.valueOf(8), copied.getMaxResults());
        Assert.assertEquals(Arrays.asList("id", "title"), copied.getOutputFields());
        Assert.assertArrayEquals(new float[]{1f, 2f}, copied.getVector(), 0f);
        Assert.assertEquals("trace-1", copied.getMetadata("traceId"));
        Assert.assertEquals(
            "tenant = \"a\" OR (status IN (\"ready\",\"pending\"))",
            copied.toFilterExpression());
    }

    @Test
    public void shouldBuildFormalNullAndNotPredicates() {
        SearchWrapper wrapper = new SearchWrapper()
            .isNull("deletedAt")
            .isNotNull("requiredAt")
            .not(group -> group.eq("status", "deleted").eq(Connector.OR, "status", "hidden"));

        Assert.assertEquals(
            "deletedAt IS NULL AND requiredAt IS NOT NULL AND NOT(status = \"deleted\" OR status = \"hidden\")",
            wrapper.toFilterExpression());
    }

    private void assertInvalid(Runnable invocation, String expectedMessage) {
        try {
            invocation.run();
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        } catch (NullPointerException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

}
