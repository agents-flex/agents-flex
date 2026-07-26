/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mariadb;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class MariaDBVectorUtilTest {

    @Test
    public void shouldRoundTripVectorText() {
        float[] vector = new float[]{1.0f, -0.25f, 0.125f};
        String text = MariaDBVectorUtil.toText(vector);
        assertEquals("[1.0,-0.25,0.125]", text);
        assertArrayEquals(vector, MariaDBVectorUtil.fromText(text), 0.00001f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNonFiniteValues() {
        MariaDBVectorUtil.toText(new float[]{Float.NaN});
    }
}
