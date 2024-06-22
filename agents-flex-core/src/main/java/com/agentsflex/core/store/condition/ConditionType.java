package com.agentsflex.core.store.condition;

public enum ConditionType {
    EQ(" = "),
    NE(" != "),
    GT(" > "),
    GE(" >= "),
    LT(" < "),
    LE(" <= "),
    IN(" IN "),
    NIN(" MIN "),
    BETWEEN(" BETWEEN "),
    ;

    private final String defaultSymbol;

    ConditionType(String defaultSymbol) {
        this.defaultSymbol = defaultSymbol;
    }

    public String getDefaultSymbol() {
        return defaultSymbol;
    }
}
