package com.agentsflex.store;

public interface StoreResult {
    StoreResult DEFAULT_SUCCESS = new DefaultResult() ;

    boolean isSuccess();

    class DefaultResult implements StoreResult {
        @Override
        public boolean isSuccess() {
            return true;
        }
    }
}
