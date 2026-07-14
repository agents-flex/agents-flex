/*
 * Copyright 2026 HojoAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentsflex.audio.volc.protocol;

public enum VersionBits {
    Version1((byte) 1),
    Version2((byte) 2),
    Version3((byte) 3),
    Version4((byte) 4),
    ;

    private final byte value;

    VersionBits(byte b) {
        this.value = b;
    }

    public byte getValue() {
        return value;
    }

    public static VersionBits fromValue(int value) {
        for (VersionBits v : VersionBits.values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown VersionBits value: " + value);
    }
}
