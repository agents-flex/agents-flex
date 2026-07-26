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

/** MariaDB 原生向量距离函数。 */
public enum MariaDBDistanceType {
    COSINE("VEC_DISTANCE_COSINE"),
    EUCLIDEAN("VEC_DISTANCE_EUCLIDEAN");

    private final String functionName;

    MariaDBDistanceType(String functionName) {
        this.functionName = functionName;
    }

    String getFunctionName() {
        return functionName;
    }
}
