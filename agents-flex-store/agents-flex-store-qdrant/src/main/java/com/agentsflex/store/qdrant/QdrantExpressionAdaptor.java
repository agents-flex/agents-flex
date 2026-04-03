package com.agentsflex.store.qdrant;

import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.ExpressionAdaptor;

public class QdrantExpressionAdaptor implements ExpressionAdaptor {

    public static final QdrantExpressionAdaptor DEFAULT = new QdrantExpressionAdaptor();

    @Override
    public String toOperationSymbol(ConditionType type) {
        if (type == ConditionType.EQ) {
            return " == ";
        }
        return type.getDefaultSymbol();
    }
}
