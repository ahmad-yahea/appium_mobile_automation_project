package automation.report;

import io.qualityplus.flutter.driver.AppiumFlutterDriver;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps one scenario's step-object (e.g. {@code SignupSteps}) driver
 * interactions so every public step reports its own pass/fail outcome into a
 * {@link ScenarioReport}, and so a red validation-error banner appearing
 * after a step is treated the same as any other step failure.
 *
 * A step that throws is recorded as failed and the exception is rethrown
 * unchanged - callers (the {@code @Test}/{@code @RetryingTest} method) see
 * exactly what they'd have seen without this wrapper, so a failing step
 * still fails the whole scenario immediately; this only adds the pass/fail
 * log trail around that existing behavior.
 */
public class StepExecutor {

	private static final Logger logger = LoggerFactory.getLogger(StepExecutor.class);

	/**
	 * Flutter render-tree class-name markers for the framework's two
	 * built-in error-surface widgets. This is a generic, unconfirmed
	 * placeholder - see the class javadoc below on
	 * {@link #assertNoErrorBanner}: if this app renders validation errors
	 * through a different (e.g. custom-styled) widget, this check won't
	 * catch it and needs to be pointed at that widget's own marker once
	 * confirmed live, the same way every ValueKey in {@code SignupSteps} is
	 * only added once confirmed against the running app.
	 */
	private static final String[] ERROR_BANNER_MARKERS = {"MaterialBanner", "SnackBar"};

	private final AppiumFlutterDriver driver;
	private final ScenarioReport report;

	public StepExecutor(AppiumFlutterDriver driver, ScenarioReport report) {
		this.driver = driver;
		this.report = report;
	}

	/** Runs a step that returns a value, reporting its outcome. */
	public <T> T run(String stepName, Supplier<T> action) {
		int stepNumber = report.results().size() + 1;
		logger.info("STEP START - {}. {}", stepNumber, stepName);
		try {
			T result = action.get();
			assertNoErrorBanner(stepName);
			report.recordPassed(stepName);
			logger.info("STEP PASSED - {}. {}", stepNumber, stepName);
			return result;
		} catch (RuntimeException | AssertionError e) {
			String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			report.recordFailed(stepName, detail);
			logger.error("STEP FAILED - {}. {}: {}", stepNumber, stepName, detail);
			throw e;
		}
	}

	/** Runs a step with no return value, reporting its outcome. */
	public void run(String stepName, Runnable action) {
		run(stepName, () -> {
			action.run();
			return null;
		});
	}

	/**
	 * Fails the current step if a validation-error banner is on screen -
	 * see the class-level javadoc and {@link #ERROR_BANNER_MARKERS} for why
	 * this is a generic, best-effort check rather than a confirmed
	 * ValueKey/widget lookup. A single immediate read of the render tree,
	 * not polled - tighten with a short poll window once the real banner
	 * widget and its appear-timing are confirmed against the app.
	 */
	private void assertNoErrorBanner(String afterStep) {
		String renderTree = driver.getRenderTree();
		for (String marker : ERROR_BANNER_MARKERS) {
			if (renderTree.contains(marker)) {
				Assertions.fail("Red error banner appeared after step '" + afterStep
						+ "' - input or tap was not accepted");
			}
		}
	}
}
