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

    public static void println(String message, Object... args) {
        logger.info(String.format(message, args));
    }

    public static void warn(String s, Exception e) {
        logger.warn(s + ": " + e.getMessage());
    }

    interface Logger {
        void info(String message);

        default void warn(String message) {
            info(message);
        }
    }
}
