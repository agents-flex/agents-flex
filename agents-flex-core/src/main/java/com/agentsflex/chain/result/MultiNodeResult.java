package com.agentsflex.chain.result;

import com.agentsflex.chain.NodeResult;

import java.util.ArrayList;
import java.util.List;

public class MultiNodeResult implements NodeResult<List<Object>> {

    private List<Object> value;

    public static MultiNodeResult ofResults(List<NodeResult<?>> nodeResults) {
        MultiNodeResult result = new MultiNodeResult();
        result.value = new ArrayList<>();
        for (NodeResult<?> nodeResult : nodeResults) {
            if (nodeResult instanceof SingleNodeResult) {
                result.value.add(nodeResult.getValue());
            } else if (nodeResult instanceof MultiNodeResult) {
                result.value.addAll(((MultiNodeResult) nodeResult).value);
            }
        }
        return result;
    }

    public static MultiNodeResult ofValues(List<Object> value) {
        MultiNodeResult result = new MultiNodeResult();
        result.value = value;
        return result;
    }

    @Override
    public List<Object> getValue() {
        return null;
    }

    public void setValue(List<Object> value) {
        this.value = value;
    }
}
