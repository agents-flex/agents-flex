package com.agentsflex.message;

import java.io.Serializable;

public enum MessageStatus implements Serializable {
    START(1),
    MIDDLE(2),
    END(3),
    UNKNOW(9),
    ;
    private int value;

    MessageStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
