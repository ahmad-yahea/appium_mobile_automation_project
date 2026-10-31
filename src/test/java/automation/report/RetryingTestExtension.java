package automation.report;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.TestAbortedException;

/**
 * Backs {@link RetryingTest}. Structurally mirrors junit-pioneer's
 * {@code RetryingTestExtension}/{@code FailedTestRetrier} (verified by
 * decompiling junit-pioneer 2.3.0): a stateful {@link Retrier} per test
 * method drives a lazy stream of attempts, and a failed attempt is either
 * reported as an aborted/skipped retry (if more attempts remain and the
 * exception is one of {@link RetryingTest#onExceptions()}), a real failure
 * (any other exception, on any attempt - never retried), or a final real
 * failure once attempts are exhausted. That final failure is the last
 * attempt's own exception, rethrown unchanged so surefire/the IDE show the
 * real cause (with any earlier attempts' failures attached as suppressed),
 * rather than a wrapper that hides it.
 *
 * The one addition over junit-pioneer: {@link #effectiveMaxAttempts} lets
 * the compiled-in {@code maxAttempts} be overridden at runtime via the
 * {@code retry.maxAttempts}/{@code retry.skip} system properties, so a
 * local run can skip retries without changing what CI runs with.
 */
public class RetryingTestExtension implements TestTemplateInvocationContextProvider, TestExecutionExceptionHandler {

	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RetryingTestExtension.class);

	@Override
	public boolean supportsTestTemplate(ExtensionContext context) {
		return AnnotationSupport.isAnnotated(context.getRequiredTestMethod(), RetryingTest.class);
	}

	@Override
	public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
		Retrier retrier = retrierFor(context);
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(retrier, Spliterator.ORDERED), false);
	}

	@Override
	public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
		// The exception callback fires on the per-attempt (child) context, but
		// provideTestTemplateInvocationContexts stored the Retrier on the
		// parent/template-level context - looking it up on the wrong context
		// would silently create a new Retrier per attempt and break counting.
		ExtensionContext parent = context.getParent()
				.orElseThrow(() -> new IllegalStateException("Cannot retry without parent context for " + context));
		retrierFor(parent).failed(throwable);
	}

	private static Retrier retrierFor(ExtensionContext context) {
		Method method = context.getRequiredTestMethod();
		return context.getStore(NAMESPACE).getOrComputeIfAbsent(method, key -> Retrier.createFor(method), Retrier.class);
	}

	/**
	 * Resolves how many attempts a scenario actually gets at runtime.
	 * {@code retry.skip=true} forces a single attempt (wins over
	 * {@code retry.maxAttempts} if both are set); {@code retry.maxAttempts=<n>}
	 * overrides the annotation's compiled-in value; anything unset or
	 * malformed falls back to the annotation's value unchanged, so CI and a
	 * plain {@code mvn test} are never affected.
	 */
	private static int effectiveMaxAttempts(int annotationMaxAttempts) {
		if (Boolean.parseBoolean(System.getProperty("retry.skip"))) {
			return 1;
		}
		String override = System.getProperty("retry.maxAttempts");
		if (override != null && !override.isBlank()) {
			try {
				int parsed = Integer.parseInt(override.trim());
				if (parsed >= 1) {
					return parsed;
				}
			} catch (NumberFormatException ignored) {
				// Malformed value - fall back to the compiled-in default rather than crashing the run.
			}
		}
		return annotationMaxAttempts;
	}

	/** One test method's shared, stateful retry counter - see {@link #retrierFor}. */
	private static final class Retrier implements Iterator<TestTemplateInvocationContext> {

		private final int maxAttempts;
		private final Class<? extends Throwable>[] onExceptions;
		private final List<Throwable> seenFailures = new ArrayList<>();

		private int attemptsSoFar = 0;
		private boolean seenUnexpectedException = false;

		static Retrier createFor(Method method) {
			RetryingTest annotation = AnnotationSupport.findAnnotation(method, RetryingTest.class)
					.orElseThrow(() -> new IllegalStateException("@RetryingTest not present on " + method));
			int effective = effectiveMaxAttempts(annotation.maxAttempts());
			return new Retrier(effective, annotation.onExceptions());
		}

		private Retrier(int maxAttempts, Class<? extends Throwable>[] onExceptions) {
			this.maxAttempts = maxAttempts;
			this.onExceptions = onExceptions;
		}

		@Override
		public boolean hasNext() {
			if (attemptsSoFar == 0) {
				return true;
			}
			return !seenUnexpectedException && attemptsSoFar < maxAttempts;
		}

		@Override
		public TestTemplateInvocationContext next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			attemptsSoFar++;
			return new RetryingTestInvocationContext(attemptsSoFar);
		}

		/** Always throws - see the class javadoc's abort-vs-fail decision order. */
		void failed(Throwable throwable) throws Throwable {
			if (throwable instanceof TestAbortedException) {
				throw new TestAbortedException(
						"Test execution was skipped, possibly because of a failed assumption.", throwable);
			}

			boolean expected = onExceptions.length == 0
					|| Arrays.stream(onExceptions).anyMatch(c -> c.isInstance(throwable));
			if (!expected) {
				seenUnexpectedException = true;
				throw throwable;
			}

			if (hasNext()) {
				TestAbortedException aborted = new TestAbortedException(
						String.format("Attempt %d (of up to %d) failed ~> will retry", attemptsSoFar, maxAttempts),
						throwable);
				seenFailures.add(aborted);
				throw aborted;
			}

			// Attempts exhausted: fail with this attempt's own exception, unchanged,
			// so surefire's <failure>/the IDE point straight at the real cause
			// (a wrapper here would only show its own headline, hiding the step
			// that failed). Earlier attempts' failures ride along as suppressed.
			for (Throwable earlier : seenFailures) {
				throwable.addSuppressed(earlier);
			}
			throw throwable;
		}
	}

	private record RetryingTestInvocationContext(int attemptIndex) implements TestTemplateInvocationContext {
		@Override
		public String getDisplayName(int invocationIndex) {
			return "attempt " + attemptIndex;
		}
	}
}
