/**
 * The suite's actual scenario entry points - what Surefire discovers and
 * runs. Each class extends {@link automation.base.FlutterBaseTest}, which owns
 * the Appium server/Flutter Driver session lifecycle and a fresh
 * {@link automation.report.ScenarioReport} per test. A scenario method never
 * locates app elements itself; instead it composes public step methods from
 * {@link automation.steps} (each step self-reports pass/fail through
 * {@link automation.report.StepExecutor}) using input from
 * {@link automation.data}, and, where a scenario needs a premium/invited user,
 * {@link automation.clients}.
 *
 * <p>Methods are {@link automation.report.RetryingTest @RetryingTest(maxAttempts
 * = 3, ...)} (an in-repo replacement for junit-pioneer's annotation of the
 * same name - see {@link automation.report.RetryingTestExtension}), not plain
 * {@code @Test}: each retry is a full fresh {@code @BeforeEach}/
 * {@code @AfterEach} cycle (new Appium server, new driver session), so a
 * scenario is automatically re-run from scratch on a connection-level
 * failure ({@code WebDriverException}/{@code IOException}, plus
 * {@code UncheckedIOException} for {@link automation.tests.JourneyHighschoolTest}'s
 * Postman CLI call). A real assertion failure - a missing element, the
 * red-banner check, an unexpected app state - is never in
 * {@code onExceptions}, so it fails the scenario immediately instead of
 * being retried and potentially masked. See {@link automation.report} for the
 * per-step pass/fail trail every scenario produces.
 *
 * <p>The compiled-in {@code maxAttempts = 3} is what CI and any plain
 * {@code mvn test} run use. Locally, pass {@code -Dretry.maxAttempts=1} (or
 * {@code -Dretry.skip=true}, equivalent) to {@code mvn test} /
 * {@code scripts/run-tests.sh} to disable retries for a single run - useful
 * when iterating on a real failure you don't want silently retried. If both
 * are set, {@code retry.skip=true} wins.
 *
 * <p>Current entry points:
 * <ul>
 *   <li>{@link automation.tests.SignupUniversityUserTypeTest} - complete
 *       3-screen signup for the University education type.</li>
 *   <li>{@link automation.tests.SignupHighschoolUserTypeTest} - complete
 *       3-screen signup for the Highschool education type.</li>
 *   <li>{@link automation.tests.SignupProfessionalUserTypeTest} - Professional
 *       signup; stops after screen 2 until screen 3's ValueKeys are
 *       confirmed against the running app (see
 *       {@link automation.steps.SignupSteps}).</li>
 *   <li>{@link automation.tests.JourneyHighschoolTest} - invites a premium
 *       highschool user via {@link automation.clients.InvitationApiClient},
 *       signs up as them, then drives as far into the post-signup journey
 *       via {@link automation.steps.JourneySteps} as the app currently
 *       allows.</li>
 * </ul>
 */
package automation.tests;
