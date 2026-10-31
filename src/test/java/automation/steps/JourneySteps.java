package automation.steps;

import automation.base.FlutterBaseTest;
import automation.report.ScenarioReport;
import automation.report.StepExecutor;
import automation.tests.JourneyHighschoolTest;
import io.qualityplus.flutter.driver.AppiumFlutterDriver;

/**
 * Page-object-style step methods for the app flow that continues past
 * signup, driven via the Appium Flutter Driver - see {@link
 * FlutterBaseTest} for why this flow can't use UiAutomator2.
 *
 * Mirrors {@link SignupSteps}'s conventions: locators are Flutter
 * ValueKeys from the automation id registry, not accessibility
 * ids/labels. Only add a named step method once its underlying key has
 * been confirmed against the app - don't invent key names ahead of that.
 * Shared driver/tapByKey plumbing lives in {@link BaseFlutterSteps}.
 *
 * TODO: {@code content_continue_button} is a generic content-screen key, not
 * part of the highschool journey - see {@link #tapContentContinue}.
 *
 * The personality-intro screen's two buttons -
 * {@code personality_intro_start_quiz_button} and
 * {@code personality_intro_maybe_later_button} - were confirmed live against
 * an earlier build to be unwired ({@code flutter:waitFor} timed out for
 * both, and a native page-source dump showed no key on either, only the
 * visible label). The mobile team's QA flow doc for Daily Intelligence /
 * Impact of Self Awareness lists {@code personality_intro_maybe_later_button}
 * as working on the current automation staging APK, so this may already be
 * fixed app-side - {@link #tapPersonalityIntroMaybeLater} is unchanged but
 * needs re-confirming on a live run rather than assumed fixed from the doc
 * alone.
 *
 * The Daily Intelligence methods below ({@code *DailyIntelligence*}) are
 * sourced from that same flow doc and from the app's own {@code
 * ContentPieceIds} constants (not yet independently confirmed live against
 * this build the way the signup keys were). Treat a timeout on any of them
 * as "needs re-confirming against the app", not necessarily a test bug.
 *
 * The roadmap-navigation methods ({@link #awaitRoadmap}, {@code open*}) are
 * a deliberate exception to the ValueKey convention: confirmed live against
 * this staging APK that the roadmap stage/module/section/item cards carry
 * <em>no</em> ValueKey (every {@code journey_*_card_*} probe times out while
 * the visible labels resolve at once) and that the roadmap animates
 * continuously (so a frame-synced {@code waitFor} never returns). Until the
 * mobile team wires ValueKeys onto those cards, {@link #tapRoadmapLabel}
 * locates them by visible label with frame sync off - see its javadoc.
 */
public class JourneySteps extends BaseFlutterSteps {

	private final StepExecutor executor;

	/**
	 * @param driver the Flutter Driver session to drive post-signup journey screens through
	 * @param report the current scenario's step report - see {@link StepExecutor}
	 */
	public JourneySteps(AppiumFlutterDriver driver, ScenarioReport report) {
		super(driver);
		this.executor = new StepExecutor(driver, report);
	}

	// ------------------------------------------------------------------
	// Journey steps
	// ------------------------------------------------------------------

	/**
	 * Taps "Continue" on a post-signup content screen using the generic
	 * {@code content_continue_button} key. No next-screen check yet - see
	 * the class javadoc on not inventing key names ahead of confirmation.
	 *
	 * NOT part of the highschool journey: confirmed via a render-tree dump
	 * that {@link JourneyHighschoolTest} lands directly on the
	 * personality-intro screen after signup, with no intervening content
	 * screen. Kept here since {@code content_continue_button} is a real,
	 * implemented key (confirmed against the app's compiled Dart code) that
	 * other journeys may still use - don't wire it into the highschool flow.
	 */
	public void tapContentContinue() {
		executor.run("tapContentContinue", () -> tapByKey("content_continue_button"));
	}

	/**
	 * Taps "Maybe Later" on the personality-intro screen.
	 *
	 * See the class javadoc: an earlier build had this key unwired to its
	 * widget; the mobile team's flow doc lists it as working on the current
	 * automation staging APK, but that hasn't been re-confirmed live here.
	 */
	public void tapPersonalityIntroMaybeLater() {
		executor.run("tapPersonalityIntroMaybeLater", () -> tapByKey("personality_intro_maybe_later_button"));
	}
	
	// ------------------------------------------------------------------
	// Daily Intelligence (post-signup, before the Journey roadmap is usable)
	// ------------------------------------------------------------------

	/**
	 * Taps "Start my journey" on the Daily Intelligence welcome overlay, if
	 * it appears. Only new free users see this welcome step; premium and
	 * returning users skip straight to the reflection overlay (per the
	 * mobile team's flow doc), so its absence here is expected, not a
	 * failure - see {@link BaseFlutterSteps#tapByKeyIfPresent}.
	 */
	public void startDailyIntelligenceIfWelcomeShown() {
		executor.run("startDailyIntelligenceIfWelcomeShown",
				() -> tapByKeyIfPresent("daily_intelligence_start_journey_button", DEFAULT_WAIT_MS));
	}

	/** Types the given answer into the Daily Intelligence reflection field. */
	public void answerDailyIntelligence(String answer) {
		executor.run("answerDailyIntelligence(" + answer + ")",
				() -> waitAndType("daily_intelligence_answer_field", answer));
	}

	/** Submits the typed Daily Intelligence reflection answer. */
	public void submitDailyIntelligence() {
		executor.run("submitDailyIntelligence", () -> tapByKey("daily_intelligence_submit_button"));
	}

	/**
	 * Closes the Daily Intelligence reflection overlay without answering it.
	 * Per the mobile team's flow doc, this hides reflection for the rest of
	 * the day - it can be reopened later via the {@code
	 * daily_intelligence_ready_banner} on the roadmap, not through this step
	 * object (not yet needed by any current flow).
	 *
	 * Tapped natively (see {@link BaseFlutterSteps#tapByKeyNative}) rather
	 * than through the Flutter Driver: confirmed live that a plain {@link
	 * #tapByKey} here doesn't dismiss the overlay (same class of issue
	 * {@code SignupSteps}' education-type cards have with a Flutter Driver
	 * click not reliably registering). If this still doesn't dismiss the
	 * overlay, the ValueKey itself may not be wired to this button on the
	 * current build - re-confirm against a render-tree/page-source dump
	 * before assuming the tap mechanism is at fault again.
	 */
	public void closeDailyIntelligenceReflection() {
		executor.run("closeDailyIntelligenceReflection", () -> {
			dumpNativePageSource(); // TEMP: diagnosing why the close tap doesn't register
			tapByKeyNative("daily_intelligence_reflection_close_button");
		});
	}

	/** Taps "Continue to journey" on the Daily Intelligence success screen. */
	public void continueToJourney() {
		executor.run("continueToJourney", () -> tapByKey("daily_intelligence_continue_button"));
	}

	// ------------------------------------------------------------------
	// Journey roadmap navigation
	// ------------------------------------------------------------------

	/**
	 * Waits out the post-signup SplashScreen/Lottie animation and settles on
	 * the Journey roadmap. Call once before the {@code open*} steps below.
	 *
	 * The wait is done with frame sync off: the roadmap animates continuously
	 * (pulsing active node, animated dashed path), so a frame-synced
	 * {@code waitFor} never returns on it - see {@link
	 * BaseFlutterSteps#withFrameSyncOff}.
	 */
	public void awaitRoadmap() {
		executor.run("awaitRoadmap", () -> withFrameSyncOff(() -> {
			if (!awaitText("Self Discovery", 45_000)) {
				throw new IllegalStateException("Journey roadmap ('Self Discovery') never appeared after signup");
			}
			sleep(1_500);
		}));
	}

	/**
	 * Taps a roadmap card by its visible label, with frame sync off, and
	 * (when given) asserts the next screen's marker label appears.
	 *
	 * Interim locator strategy: on the current staging APK the roadmap
	 * stage/module/section/item cards carry <em>no ValueKey</em> - a
	 * {@code ByValueKey} probe of {@code journey_stage_card_*} /
	 * {@code journey_module_card_*} / {@code journey_section_card_*} /
	 * {@code journey_item_piece_card_*} (and several name variants) all time
	 * out while the labels resolve instantly - and the native a11y tree for
	 * the screen is empty. Once the mobile team wires ValueKeys onto these
	 * cards, switch back to {@link BaseFlutterSteps#tapByKey} (still inside
	 * {@link BaseFlutterSteps#withFrameSyncOff}).
	 */
	private void tapRoadmapLabel(String label, String expectedNextLabel) {
		withFrameSyncOff(() -> {
			tapVisibleText(label);
			sleep(1_500);
			if (expectedNextLabel != null && !awaitText(expectedNextLabel, DEFAULT_WAIT_MS)) {
				saveScreenshot("after-" + label.replaceAll("[^A-Za-z0-9]+", "-"));
				dumpRenderTree();
				throw new IllegalStateException("Tapped roadmap label '" + label + "' but '"
						+ expectedNextLabel + "' never appeared - the tap may not have registered");
			}
		});
	}

	/** Opens the Self Discovery stage on the Journey roadmap. */
	public void openSelfDiscoveryStage() {
		executor.run("openSelfDiscoveryStage", () -> tapRoadmapLabel("Self Discovery", "Identity"));
	}

	/** Opens the Identity module within the Self Discovery stage. */
	public void openIdentityModule() {
		executor.run("openIdentityModule", () -> tapRoadmapLabel("Identity", "Awareness of Identity"));
	}

	/** Opens the Awareness of Identity section within the Identity module. */
	public void openAwarenessOfIdentitySection() {
		executor.run("openAwarenessOfIdentitySection", () -> tapRoadmapLabel("Awareness of Identity", "impact of self-awareness"));
	}

	/**
	 * Opens the "impact of self-awareness" lesson row within Awareness of
	 * Identity. The row label is lower-case and hyphenated in the app
	 * ("impact of self-awareness", not "Impact of Self Awareness").
	 */
	public void openImpactOfSelfAwarenessItem() {
		executor.run("openImpactOfSelfAwarenessItem", () -> tapRoadmapLabel("impact of self-awareness", null));
	}
}
