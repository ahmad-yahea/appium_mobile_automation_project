package automation.report;

import automation.base.FlutterBaseTest;
import automation.report.ScenarioReport.StepResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs each scenario's final step-by-step outcome once it finishes -
 * registered once via {@code @ExtendWith} on {@link FlutterBaseTest} and
 * inherited by every test class. Reads the just-finished test instance's
 * {@link ScenarioReport} (populated by {@link StepExecutor} as the scenario
 * ran) and logs a fixed-prefix summary ({@code SCENARIO PASSED}/
 * {@code SCENARIO FAILED}) that {@code scripts/parse-surefire-results.sh}
 * pulls back out of the surefire report's captured output.
 *
 * A {@code @RetryingTest} scenario re-runs its whole lifecycle (fresh
 * {@code @BeforeEach}, fresh {@link ScenarioReport}) per attempt, so a
 * connection-hangup retry logs one summary per attempt - the step trail for
 * an abandoned attempt stays visible alongside the eventual outcome, rather
 * than being silently discarded.
 */
public class ScenarioReportExtension implements TestWatcher {

	private static final Logger logger = LoggerFactory.getLogger(ScenarioReportExtension.class);

	@Override
	public void testSuccessful(ExtensionContext context) {
		ScenarioReport report = reportOf(context);
		logger.info("SCENARIO PASSED - {} ({} steps)", scenarioName(context), report.results().size());
	}

	@Override
	public void testFailed(ExtensionContext context, Throwable cause) {
		ScenarioReport report = reportOf(context);
		StepResult failure = report.firstFailure();
		if (failure != null) {
			logger.error("SCENARIO FAILED - {} at step {} '{}': {}", scenarioName(context),
					failure.index(), failure.name(), failure.detail());
		} else {
			// Failed before any step ran (e.g. @BeforeEach couldn't start a session).
			logger.error("SCENARIO FAILED - {} before any step ran: {}", scenarioName(context),
					cause.getMessage());
		}
	}

	private ScenarioReport reportOf(ExtensionContext context) {
		return ((FlutterBaseTest) context.getRequiredTestInstance()).report();
	}

	private String scenarioName(ExtensionContext context) {
		return context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName();
	}
}
