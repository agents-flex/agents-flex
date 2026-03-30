package com.agentsflex.store.milvus;

import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.ExpressionAdaptor;

public class MilvusExpressionAdaptor implements ExpressionAdaptor {

    public static final MilvusExpressionAdaptor DEFAULT = new MilvusExpressionAdaptor();

    @Override
    public String toOperationSymbol(ConditionType type) {
        if (type == ConditionType.EQ) {
            return " == ";
        }
        return type.getDefaultSymbol();
    }
}
