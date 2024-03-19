package com.agentsflex.store.condition;

public enum ConditionType {
    EQ(" = "),
    NE(" != "),
    GT(" >= "),
    GE(" >= "),
    LT(" < "),
    LE(" <= "),
    IN(" IN "),
    NIN(" MIN "),
    NOT(" NOT "),
    ;

    private final String defaultSymbol;

    ConditionType(String defaultSymbol) {
        this.defaultSymbol = defaultSymbol;
    }

    public String getDefaultSymbol() {
        return defaultSymbol;
    }
}
