package com.example.Campagin.config;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetryUtils {

    private static final Logger logger = LoggerFactory.getLogger(RetryUtils.class);

    public static <T> T retry(int maxAttempts, long delayMillis, RetryableOperation<T> operation) {
        int attempt = 1;
        Exception lastException = null;

        while (attempt <= maxAttempts) {
            try {
                logger.warn("🔁 Attempt {} of {}...", attempt, maxAttempts);
                return operation.execute();
            } catch (Exception e) {
                lastException = e;

                String simpleError = e.getMessage() != null ? e.getMessage().split("\n")[0] : "Unknown error";

                if (attempt < maxAttempts) {
                    logger.warn("⚠️ Attempt {} failed: {}. Retrying in {} ms...", attempt, simpleError, delayMillis);
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    logger.error("🚫 All {} attempts failed. Giving up. Last error: {}", maxAttempts, simpleError);
                }

                attempt++;
            }
        }

        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts.", lastException);
    }

    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}
