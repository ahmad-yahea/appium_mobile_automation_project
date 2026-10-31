package automation.report;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * In-repo replacement for junit-pioneer's {@code @RetryingTest}, kept to
 * exactly the two attributes every call site uses. The difference from
 * junit-pioneer's version: the retry count is resolvable at runtime via the
 * {@code retry.maxAttempts}/{@code retry.skip} system properties (see
 * {@link RetryingTestExtension}), which junit-pioneer's compile-time-only
 * annotation doesn't support - useful for skipping the automatic
 * connection-hangup retries during local iteration without touching this
 * annotation's compiled-in value, which stays what CI runs with.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Execution(ExecutionMode.SAME_THREAD)
@TestTemplate
@ExtendWith(RetryingTestExtension.class)
public @interface RetryingTest {

	/** Compiled-in attempt count; see {@link RetryingTestExtension} for the runtime override. */
	int maxAttempts();

	/** Exception types that trigger a retry; empty means every exception is retriable. */
	Class<? extends Throwable>[] onExceptions() default {};
}
