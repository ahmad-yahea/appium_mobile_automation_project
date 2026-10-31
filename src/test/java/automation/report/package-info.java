/**
 * Per-scenario step reporting - {@link automation.report.ScenarioReport} tracks
 * each step's pass/fail outcome, {@link automation.report.StepExecutor} wraps
 * a step-object's driver interactions to populate it (including the
 * red-error-banner check), and {@link automation.report.ScenarioReportExtension}
 * logs the final per-scenario summary that {@code scripts/parse-surefire-results.sh}
 * reads back out of the surefire report's captured output.
 */
package automation.report;
