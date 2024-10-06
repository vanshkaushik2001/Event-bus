package retry;


import exception.RetryAbleException;
import exception.RetryLimitExceededException;

import java.util.function.Function;

public abstract class RetryAlgorithm<PARAMETER, RESULT> {

    private final int maxAttempts;
    private final Function<Integer, Long> retryTimeCalculator;

    public RetryAlgorithm(final int maxAttempts,
                          final Function<Integer, Long> retryTimeCalculator) {
        this.maxAttempts = maxAttempts;
        this.retryTimeCalculator = retryTimeCalculator;
    }

    public RESULT attempt(Function<PARAMETER, RESULT> task, PARAMETER parameter, int attempts) {
        try {
            return task.apply(parameter);
        } catch (Exception e) {
            if (e.getCause() instanceof RetryAbleException) {
                if (attempts == maxAttempts) {
                    throw new RetryLimitExceededException();
                } else {
                    final RESULT result = attempt(task, parameter, attempts + 1);
                    try {
                        Thread.sleep(retryTimeCalculator.apply(attempts));
                        return result;
                    } catch (InterruptedException interrupt) {
                        throw new RuntimeException(interrupt);
                    }
                }
            } else {
                throw new RuntimeException(e);
            }
        }
    }
}
