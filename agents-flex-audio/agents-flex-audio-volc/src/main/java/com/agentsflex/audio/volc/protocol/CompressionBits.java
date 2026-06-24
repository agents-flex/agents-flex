package com.agentsflex.audio.volc.protocol;

public enum CompressionBits {
    None_((byte) 0),
    Gzip((byte) 0b1),
    Custom((byte) 0b11),
    ;

    private final byte value;

    CompressionBits(byte b) {
        this.value = b;
    }

    public byte getValue() {
        return value;
    }

    public static CompressionBits fromValue(int value) {
        for (CompressionBits v : CompressionBits.values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown CompressionBits value: " + value);
    }
}
