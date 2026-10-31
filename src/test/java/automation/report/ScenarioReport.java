package automation.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The step-by-step outcome of one scenario, in the order steps were
 * attempted. Populated by {@link StepExecutor} as a scenario runs; read back
 * by {@link ScenarioReportExtension} once the scenario finishes to log the
 * final summary.
 *
 * Scenarios stop at their first failing step (see {@link StepExecutor}), so
 * this never holds a "not run" entry - only steps actually attempted.
 */
public class ScenarioReport {

	/** One step's recorded outcome. */
	public record StepResult(int index, String name, boolean passed, String detail) {
	}

	private final List<StepResult> results = new ArrayList<>();

	/** Records a step that completed without throwing. */
	void recordPassed(String name) {
		results.add(new StepResult(results.size() + 1, name, true, null));
	}

	/** Records a step that threw, along with the failure's message. */
	void recordFailed(String name, String detail) {
		results.add(new StepResult(results.size() + 1, name, false, detail));
	}

	/** Every step attempted so far, in order. */
	public List<StepResult> results() {
		return Collections.unmodifiableList(results);
	}

	/** The first failed step, if any. */
	public StepResult firstFailure() {
		return results.stream().filter(r -> !r.passed()).findFirst().orElse(null);
	}
}
