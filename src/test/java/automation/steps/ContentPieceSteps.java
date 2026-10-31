package automation.steps;

import automation.base.FlutterBaseTest;
import automation.report.ScenarioReport;
import automation.report.StepExecutor;
import io.qualityplus.flutter.driver.AppiumFlutterDriver;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page-object-style step methods for a single Journey lesson ("content
 * piece") screen, driven via the Appium Flutter Driver - see {@link
 * FlutterBaseTest} for why this flow can't use UiAutomator2.
 *
 * Every lesson's ValueKeys share one {@code <id_prefix>_<suffix>} shape
 * (confirmed against "Impact of Self Awareness" via its {@code ContentPieceIds}
 * Dart constant and the mobile team's flow doc), so one instance drives
 * whichever lesson its {@code idPrefix} names rather than hardcoding a
 * single lesson's ids - construct one per lesson under test, e.g.
 * {@code new ContentPieceSteps(driver, report, "impact_of_self_awareness")}.
 *
 * The intro/video/outro pages all reuse the same "start"-style CTA key
 * (they share one widget), so {@link #tapStart()} is called from whichever
 * page is on screen - callers must know which page that is; this class
 * can't distinguish them.
 *
 * {@link #continueReflectionByLabel()} is a deliberate, interim exception to
 * the ValueKey convention: the {@code <idPrefix>_continue_button} key on the
 * post-video reflection sheet is not yet confirmed wired on this staging
 * build, so that step locates the sheet's "Continue" button by visible label
 * instead - the same stopgap {@link JourneySteps#tapRoadmapLabel} uses for
 * the unkeyed roadmap cards. {@link #typeJournalReflection} and
 * {@link #returnToJourney} stay on their ValueKeys because their targets (a
 * {@code TextField} and a draggable FAB) carry no visible text to match.
 *
 * {@link #startQuiz()} taps "Start Quiz" by visible label.
 * {@link #answerAllQuizQuestions()} then drives the whole 7-question quiz: it
 * reads each question's {@code <question_id>} out of the render tree (the choice
 * rows are keyed off the <em>question</em> id, not this class's {@code idPrefix},
 * so a fixed {@code <idPrefix>_choice_N} can't reach them), taps the 4th option
 * by that derived key, and presses the advance button (auto-detected on the
 * last question).
 *
 * After the quiz, {@link #continuePastQuizCompletion()} taps "Continue" on the
 * XP screen into the next piece and {@link #exploreNow()} enters it. From the
 * XP screen on, only Flutter-context calls are safe: that "Continue" tap drops
 * the native (UiAutomator2) half of the session - see
 * {@link #continuePastQuizCompletion()}.
 */
public class ContentPieceSteps extends BaseFlutterSteps {

	private final String idPrefix;
	private final StepExecutor executor;

	/**
	 * @param driver   the Flutter Driver session to drive the lesson screen through
	 * @param report   the current scenario's step report - see {@link StepExecutor}
	 * @param idPrefix the lesson's ValueKey prefix, e.g. "impact_of_self_awareness"
	 */
	public ContentPieceSteps(AppiumFlutterDriver driver, ScenarioReport report, String idPrefix) {
		super(driver);
		this.idPrefix = idPrefix;
		this.executor = new StepExecutor(driver, report);
	}

	/**
	 * Taps the shared start/finish/continue CTA - intro's "Let's start",
	 * video's "Finish", or outro's "Continue".
	 *
	 * Wrapped in {@link BaseFlutterSteps#withFrameSyncOff}: the lesson screen
	 * animates continuously, so a frame-synced {@code waitFor} never returns
	 * on it (same issue as the Journey roadmap - see {@link JourneySteps}).
	 * The {@code _start_button} ValueKey itself is present on this build; only
	 * the frame sync blocked it.
	 */
	public void tapStart() {
		executor.run(idPrefix + ".tapStart",
				() -> withFrameSyncOff(() -> tapByKey(idPrefix + "_start_button")));
	}

	/** Skips the video, finishing the piece without opening the reflection sheet. Frame sync off - see {@link #tapStart}. */
	public void skipVideo() {
		executor.run(idPrefix + ".skipVideo",
				() -> withFrameSyncOff(() -> tapByKey(idPrefix + "_skip_video_button")));
	}

	/**
	 * Taps the "Skip" button on the post-video/outro screen by visible label,
	 * matched case-insensitively.
	 *
	 * The {@code <idPrefix>_skip_video_button} ValueKey still <em>resolves</em>
	 * on this screen but a Flutter Driver tap on it hangs the session (confirmed
	 * on a live run - same class of issue as the education-type cards and the
	 * Daily Intelligence close button). The button's own label casing isn't
	 * consistent with the video screen's ("Skip" here vs "skip" there), hence
	 * {@link BaseFlutterSteps#tapVisibleTextIgnoreCase}. Frame sync off - see
	 * {@link #tapStart}.
	 */
	public void skipByLabel() {
		executor.run(idPrefix + ".skipByLabel",
				() -> withFrameSyncOff(() -> tapVisibleTextIgnoreCase("Skip")));
	}

	/** Types the given text into the post-video reflection sheet's journal field. */
	public void typeJournalReflection(String text) {
		executor.run(idPrefix + ".typeJournalReflection", () -> waitAndType(idPrefix + "_journal_field", text));
	}

	/** Taps "Continue" on the reflection sheet. */
	public void continueReflection() {
		executor.run(idPrefix + ".continueReflection", () -> tapByKey(idPrefix + "_continue_button"));
	}

	/**
	 * Interim label-based counterpart to {@link #continueReflection()}: taps
	 * the post-video reflection sheet's "Continue" button by its visible label
	 * instead of the {@code <idPrefix>_continue_button} ValueKey, which is not
	 * yet confirmed wired on this staging build.
	 *
	 * Frame sync off - the lesson screen animates continuously, same as
	 * {@link #tapStart}. Located by exact visible text via
	 * {@link BaseFlutterSteps#tapVisibleText}; "Continue" is a common label, so
	 * if the sheet ever shows more than one Continue-like control this needs a
	 * more specific target. Switch callers back to {@link #continueReflection()}
	 * once the ValueKey is confirmed - see {@link JourneySteps#tapRoadmapLabel}
	 * for the same interim pattern on the roadmap cards.
	 */
	public void continueReflectionByLabel() {
		executor.run(idPrefix + ".continueReflectionByLabel",
				() -> withFrameSyncOff(() -> tapVisibleText("Continue")));
	}

	/** Leaves the lesson via its draggable FAB, returning to the Journey roadmap. */
	public void returnToJourney() {
		executor.run(idPrefix + ".returnToJourney", () -> tapByKey(idPrefix + "_draggable_fab"));
	}

	/** Taps "Start Quiz" on the lesson's quiz intro, by visible label. Frame sync off - see {@link #tapStart}. */
	public void startQuiz() {
		executor.run(idPrefix + ".startQuiz",
				() -> withFrameSyncOff(() -> tapVisibleText("Start Quiz")));
	}

	/** Number of questions in the lesson quiz - confirmed live via the on-screen {@code N/7} progress text. */
	private static final int QUIZ_QUESTION_COUNT = 7;
	/** Which answer choice to pick on every question, 0-based ({@code 3} = the 4th option). */
	private static final int QUIZ_ANSWER_INDEX = 3;

	/** Matches a choice ValueKey and captures the question-id prefix, e.g. {@code ..._choice_3} -> group(1). */
	private static final Pattern CHOICE_KEY = Pattern.compile("([a-z0-9_]+?)_choice_(\\d+)(?![0-9])");
	/** Render-tree tokens that are debug scaffolding, not answer labels - see {@link #quizChoiceLabel}. */
	private static final Pattern NON_LABEL = Pattern.compile(
			"MediaQuery|EdgeInsets|ByText|ELEMENT|BoxConstraints|Offset|ColorSpace|TextDecoration|Size:");

	/**
	 * Answers every question in the lesson quiz by selecting its 4th option and
	 * advancing, without any hard-coded answer text.
	 *
	 * For each of the {@value #QUIZ_QUESTION_COUNT} questions it reads the render
	 * tree, derives the question's key prefix from a {@code <question_id>_choice_N}
	 * row (the choice rows are keyed off the question id, not this class's
	 * {@code idPrefix}), asserts {@code <question_id>_choice_3} resolves, then
	 * taps the 4th option <em>by its parsed visible label</em> (a {@code
	 * ByValueKey} click on the row doesn't register as a selection on this
	 * build). It then presses the advance button - "Next Question" on questions
	 * 1-6, auto-detected on the last (see {@link #tapQuizAdvance}) - and confirms
	 * the {@code N/7} progress text has gone. If it hasn't (e.g. a "select an
	 * answer" banner because the tap didn't register) it presses again and
	 * re-selects before failing. After the last question it dumps the render
	 * tree of whatever screen follows the quiz.
	 *
	 * Whole loop runs with frame sync off - the lesson screen animates
	 * continuously, same as {@link #tapStart}.
	 */
	public void answerAllQuizQuestions() {
		for (int n = 1; n <= QUIZ_QUESTION_COUNT; n++) {
			final int questionNumber = n;
			executor.run(idPrefix + ".answerQuestion" + questionNumber,
					() -> withFrameSyncOff(() -> answerCurrentQuestion(questionNumber)));
		}
		dumpRenderTree(); // stop point: capture whatever screen follows the quiz
	}

	/**
	 * Selects the 4th option on the question currently on screen and advances
	 * past it. See {@link #answerAllQuizQuestions()} for the overall contract.
	 *
	 * "Advanced" is judged by the {@code N/7} progress text going away via the
	 * {@code ByText} finder - the quiz is a {@code PageView} that keeps the old
	 * question built in the render tree after it scrolls offstage, so a
	 * string-search of the tree can't tell whether it moved, but the finder
	 * (which skips offstage widgets) can.
	 */
	private void answerCurrentQuestion(int questionNumber) {
		String tree = driver.getRenderTree();
		String questionId = quizQuestionId(tree, questionNumber);
		String answerKey = questionId + "_choice_" + QUIZ_ANSWER_INDEX;
		String progress = questionNumber + "/" + QUIZ_QUESTION_COUNT; // e.g. "1/7"

		// The 4th option is located from the page: its key is derived from the
		// render tree (asserted to resolve), and its visible label is parsed for
		// the actual tap - a ByValueKey click on the choice row does not register
		// as a selection on this build (same class of issue as the education-type
		// cards), whereas a ByText tap does.
		driver.waitFor(driver.findElement(
				io.qualityplus.flutter.common.FlutterBy.VALUE_KEY, answerKey), DEFAULT_WAIT_MS);
		String answerLabel = quizChoiceLabel(tree, questionId, QUIZ_ANSWER_INDEX);
		if (answerLabel == null) {
			dumpRenderTree();
			throw new IllegalStateException("Q" + questionNumber + ": could not parse the 4th option's"
					+ " visible label (key " + answerKey + " resolves but a key-tap won't select it)");
		}
		logger.info("Q{}: selecting 4th option '{}' ({})", questionNumber, answerLabel, answerKey);

		tapVisibleText(answerLabel);
		sleep(700);
		tapQuizAdvance(questionNumber);
		if (awaitTextGone(progress, DEFAULT_WAIT_MS)) {
			return;
		}

		// Didn't advance: press again (a raced first tap), then - if the 4th
		// option is still on screen (e.g. a "select an answer" banner meant the
		// selection didn't take) - re-select and advance once more.
		logger.warn("Q{}: '{}' still on screen after advancing; retrying", questionNumber, progress);
		tapQuizAdvance(questionNumber);
		if (awaitTextGone(progress, DEFAULT_WAIT_MS)) {
			return;
		}
		if (awaitText(answerLabel, 2_000)) {
			tapVisibleText(answerLabel);
			sleep(700);
			tapQuizAdvance(questionNumber);
		}
		if (!awaitTextGone(progress, DEFAULT_WAIT_MS)) {
			dumpRenderTree();
			throw new IllegalStateException("Q" + questionNumber + ": progress '" + progress
					+ "' still shown after selecting the 4th option ('" + answerLabel
					+ "') and pressing the advance button repeatedly");
		}
	}

	/**
	 * Derives the current question's key prefix from the first
	 * {@code <question_id>_choice_3} row in {@code tree}. Requiring {@code _choice_3}
	 * (not {@code _choice_0}) also enforces "the question has a 4th option".
	 */
	private static String quizQuestionId(String tree, int questionNumber) {
		Matcher m = CHOICE_KEY.matcher(tree);
		while (m.find()) {
			if (Integer.parseInt(m.group(2)) == QUIZ_ANSWER_INDEX) {
				return m.group(1);
			}
		}
		throw new IllegalStateException(
				"Q" + questionNumber + ": no 4th answer option (a *_choice_" + QUIZ_ANSWER_INDEX
						+ " key) found in the render tree");
	}

	/**
	 * Best-effort read of the visible label of {@code <questionId>_choice_<idx>}
	 * from the render tree: the first sentence-like quoted string in the slice
	 * that starts at the choice's key and ends at the next choice / the
	 * follow-up "Got a different answer" field / the advance button. Returns
	 * {@code null} if nothing plausible is found (caller then asserts the key
	 * directly).
	 */
	private static String quizChoiceLabel(String tree, String questionId, int idx) {
		int start = tree.indexOf(questionId + "_choice_" + idx);
		if (start < 0) {
			return null;
		}
		int end = tree.indexOf(questionId + "_choice_" + (idx + 1), start);
		if (end < 0) {
			end = indexOfFirst(tree, start, "Got a different answer", "Next Question", "Finish", "Submit");
		}
		if (end < 0) {
			end = Math.min(tree.length(), start + 4000);
		}
		Matcher m = Pattern.compile("\"([\\p{L}][\\p{L}0-9 ,'’.\\-?!]{2,120})\"")
				.matcher(tree.substring(start, end));
		while (m.find()) {
			String candidate = m.group(1).trim();
			if (!NON_LABEL.matcher(candidate).find()) {
				return candidate;
			}
		}
		return null;
	}

	/** Index of the earliest occurrence of any {@code needles} at/after {@code from}, or -1. */
	private static int indexOfFirst(String haystack, int from, String... needles) {
		int best = -1;
		for (String needle : needles) {
			int at = haystack.indexOf(needle, from);
			if (at >= 0 && (best < 0 || at < best)) {
				best = at;
			}
		}
		return best;
	}

	/**
	 * Leaves the quiz-completion / XP screen via its "Continue" CTA, which
	 * chains straight into the next content piece.
	 *
	 * Flutter-context only, and every step from here on must be: confirmed live
	 * that this "Continue" tap drops the UiAutomator2 (native) half of the
	 * Appium session (a {@code NATIVE_APP} context switch right after it -
	 * {@link #saveScreenshot}, {@link #tapByKeyNative}, {@link
	 * #dumpNativePageSource} - fails with "driver was unexpectedly shut down").
	 * The Flutter driver extension keeps working. Frame sync off - see
	 * {@link #tapStart}.
	 */
	public void continuePastQuizCompletion() {
		executor.run(idPrefix + ".continuePastQuizCompletion", () -> withFrameSyncOff(() -> {
			tapVisibleTextIgnoreCase("Continue");
			if (!awaitText("Explore Now!", 15_000)) {
				dumpRenderTree();
				throw new IllegalStateException(
						"next piece ('Explore Now!') did not appear after tapping Continue on the XP screen");
			}
		}));
	}

	/**
	 * Enters the "interactive identity infographic" piece
	 * ({@code what_shapes_your_identity}) via its "Explore Now!" text button
	 * (no ValueKey on it). Dumps the render tree once inside - the infographic's
	 * own controls aren't scripted yet. Flutter-context only, frame sync off -
	 * see {@link #continuePastQuizCompletion}.
	 */
	public void exploreNow() {
		executor.run(idPrefix + ".exploreNow", () -> withFrameSyncOff(() -> {
			tapVisibleText("Explore Now!");
			sleep(2_000);
			if (!awaitTextGone("Explore Now!", DEFAULT_WAIT_MS)) {
				dumpRenderTree();
				throw new IllegalStateException("'Explore Now!' still on screen - the tap did not register");
			}
			dumpRenderTree(); // infographic interior not yet known - capture it for the next step
		}));
	}

	/**
	 * Presses the quiz's advance button. Questions 1-6 show "Next Question"; the
	 * last question's button is "Finish Quiz" (confirmed live). The remaining
	 * labels are kept as a fallback for other pieces / future changes; if none
	 * is on screen this fails with the tried list.
	 */
	private void tapQuizAdvance(int questionNumber) {
		if (tapVisibleTextIgnoreCaseIfPresent("Next Question", 6_000)) {
			return;
		}
		String[] fallbacks = {"Finish Quiz", "Next", "Finish", "Submit", "See Results", "See Result", "Done", "Continue"};
		for (String label : fallbacks) {
			if (tapVisibleTextIgnoreCaseIfPresent(label, 1_500)) {
				logger.info("Q{}: advanced via fallback button '{}'", questionNumber, label);
				return;
			}
		}
		dumpRenderTree();
		throw new IllegalStateException("Q" + questionNumber
				+ ": no quiz advance button found (tried \"Next Question\" and " + java.util.Arrays.toString(fallbacks) + ")");
	}
}
