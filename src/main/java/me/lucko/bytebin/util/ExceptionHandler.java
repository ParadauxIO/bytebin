package me.lucko.bytebin.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExceptionHandler implements Thread.UncaughtExceptionHandler {
    public static final ExceptionHandler INSTANCE = new ExceptionHandler();

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(ExceptionHandler.class);

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        LOGGER.error("Uncaught exception thrown by thread " + t.getName(), e);
        Metrics.UNCAUGHT_ERROR_COUNTER.labels(e.getClass().getSimpleName()).inc();
    }
}
