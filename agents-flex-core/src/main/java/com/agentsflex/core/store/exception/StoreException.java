package com.agentsflex.core.store.exception;

/**
 * 存储异常
 *
 * @author songyinyin
 * @since 2024/8/10 下午8:53
 */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

