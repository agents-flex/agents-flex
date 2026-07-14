package com.agentsflex.audio.volc.protocol;

public enum HeaderSizeBits {
    HeaderSize4((byte) 1),
    HeaderSize8((byte) 2),
    HeaderSize12((byte) 3),
    HeaderSize16((byte) 4),
    ;

    private final byte value;

    HeaderSizeBits(byte b) {
        this.value = b;
    }

    public byte getValue() {
        return value;
    }

    public static HeaderSizeBits fromValue(int value) {
        for (HeaderSizeBits v : HeaderSizeBits.values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown HeaderSizeBits value: " + value);
    }
}
