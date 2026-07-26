/* Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com). */
package com.agentsflex.store.clickhouse;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ClickHouseVectorUtilTest {
    @Test
    public void shouldRoundTripVectorText() {
        String text = ClickHouseVectorUtil.toText(new float[]{1, 0.25f, -2});
        assertEquals("[1.0,0.25,-2.0]", text);
        assertArrayEquals(new float[]{1, 0.25f, -2}, ClickHouseVectorUtil.fromText(text), 0.0001f);
    }

    @Test
    public void shouldRejectMissingAndNonFiniteVectors() {
        assertThrows(IllegalArgumentException.class, () -> ClickHouseVectorUtil.toText(null));
        assertThrows(IllegalArgumentException.class,
            () -> ClickHouseVectorUtil.toText(new float[]{Float.NaN}));
    }
}
