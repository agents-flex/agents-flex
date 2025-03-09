package com.agentsflex.core.util;

public class LogUtil {

    private static Logger logger = System.out::println;

    public static Logger getLogger() {
        return logger;
    }

    public static void setLogger(Logger logger) {
        LogUtil.logger = logger;
    }

    public static void println(String message) {
        logger.info(message);
    }

    interface Logger {
        void info(String message);
    }
}
