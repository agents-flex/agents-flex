package com.agentsflex.audio.volc.protocol;

public enum SerializationBits {
    Raw((byte) 0),
    JSON((byte) 0b1),
    Thrift((byte) 0b11),
    Custom((byte) 0b1111),
    ;

    private final byte value;

    SerializationBits(byte b) {
        this.value = b;
    }

    public byte getValue() {
        return value;
    }

    public static SerializationBits fromValue(int value) {
        for (SerializationBits v : SerializationBits.values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown SerializationBits value: " + value);
    }
}
