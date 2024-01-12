package com.agentsflex.vector;

import com.agentsflex.util.Metadata;


public class VectorData extends Metadata {

    // the embedding data
    private double[] data;

    public double[] getData() {
        return data;
    }

    public void setData(double[] data) {
        this.data = data;
    }
}
