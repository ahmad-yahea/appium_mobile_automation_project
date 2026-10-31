package automation.steps;

import io.qualityplus.flutter.common.FlutterBy;
import io.qualityplus.flutter.driver.AppiumFlutterDriver;
import io.qualityplus.flutter.driver.FlutterElement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;

/**
 * {@link SignupSteps}'s shared implementation layer - dropdown render-tree
 * parsing/polling, native gesture taps, and generic tap/verify helpers that
 * make the signup step methods work against Flutter's dropdown/list widgets
 * and the Flutter Driver protocol, but aren't signup steps in their own
 * right. Split out from {@link SignupSteps} once that class grew past 1,000
 * lines, so its own file stays focused on the public step methods
 * themselves.
 */
public abstract class SignupStepSupport extends BaseFlutterSteps {

	/**
	 * How long to tolerate the app not being Flutter-ready yet when tapping
	 * the very first key of a run - see
	 * {@link #tapByKeyTolerantOfStartupRace}.
	 */
	protected static final int STARTUP_READY_TIMEOUT_MS = 20_000;

	/**
	 * The date-of-birth info button opens a picker dialog that starts on a
	 * year grid; tapping a year switches to a day calendar for that year
	 * (default month, no day pre-selected - confirmed via screen
	 * recording), then CANCEL/OK confirms. Day 15 is valid in every month,
	 * so it's used as a fixed, always-valid day-of-month.
	 */
	protected static final String DATE_PICKER_CONFIRM_TEXT = "OK";
	protected static final int SAFE_DAY_OF_MONTH = 15;

	/**
	 * Dropdown suggestions build asynchronously (the university list is
	 * API-backed), so they're polled for rather than read once.
	 */
	private static final int DROPDOWN_TIMEOUT_MS = 30_000;

	/** Poll interval while waiting on a dropdown, the Continue button, or a locked field's suggestions. */
	protected static final int DROPDOWN_POLL_INTERVAL_MS = 400;

	/**
	 * A dropdown only shows about four rows at a time, so reaching a match
	 * further down takes a scroll or two. Each one is followed by a re-read,
	 * so the cap only guards against a match that can never be reached.
	 */
	private static final int MAX_DROPDOWN_SCROLLS = 6;

	/** Used only for a single-row list, where row spacing can't be measured. */
	private static final double FALLBACK_ROW_PITCH = 56.0;

	/**
	 * How long {@link SignupSteps#assertSchoolNameLocked} keeps re-checking
	 * that no dropdown opened. The highschool list isn't API-backed (unlike
	 * university's), so a genuinely unlocked field's dropdown renders
	 * quickly - this only needs to be long enough to catch one appearing
	 * late, not as long as {@link #DROPDOWN_TIMEOUT_MS}, which waits for a
	 * dropdown that's expected to show up.
	 */
	protected static final int LOCKED_FIELD_CHECK_MS = 2_000;

	/**
	 * Markers and patterns for picking the suggestion rows, and the list's
	 * scroll position, out of a render tree dump - see readDropdownState.
	 */
	private static final String LIST_TILE_MARKER = "_ListTile";
	private static final String SLIVER_CONSTRAINTS_MARKER = "SliverConstraints(";
	private static final String SLIVER_CONSTRAINTS_END_MARKER = "cacheOrigin";
	private static final Pattern ITEM_LABEL_PATTERN = Pattern.compile("║\\s+\"(.*)\"\\s*$");
	private static final Pattern ITEM_POSITION_PATTERN =
			Pattern.compile("index=(\\d+); layoutOffset=([0-9.]+)");
	private static final Pattern SCROLL_OFFSET_PATTERN =
			Pattern.compile("scrollOffset:\\s*([0-9.]+)");
	private static final Pattern VIEWPORT_EXTENT_PATTERN =
			Pattern.compile("viewportMainAxisExtent:\\s*([0-9.]+)");
	private static final Pattern DEVICE_PIXEL_RATIO_PATTERN =
			Pattern.compile("device pixel ratio:\\s*([0-9.]+)");
	private static final Pattern WHITESPACE_AND_TREE_GLYPHS = Pattern.compile("[\\s│├└─]+");

	/** @param driver the Flutter Driver session to drive signup screens through */
	protected SignupStepSupport(AppiumFlutterDriver driver) {
		super(driver);
	}

	/**
	 * Selects the Nth (0-based) suggestion from a dropdown that's currently
	 * open.
	 *
	 * Neither driver can address these rows directly: the Flutter finders
	 * have no notion of an index and return at most one element, while in the
	 * native (UiAutomator2) context the rows never reach the accessibility
	 * tree at all - a page source dumped with the list open shows the plain
	 * form and nothing else. Tapping them by text doesn't work either, since
	 * Flutter Driver's waitFor and click both block until the app stops
	 * producing frames, which never happens while a dropdown is open.
	 *
	 * What does work is the render tree, which carries the rows along with
	 * their layout offsets, so a row's position on screen can be computed and
	 * tapped as a native coordinate gesture.
	 */
	protected void selectDropdownItem(String fieldValueKey, Rectangle fieldBounds, int index) {
		DropdownState state = awaitDropdown(dropdown -> dropdown.itemAt(index) != null,
				"an item at index " + index);
		selectItem(fieldValueKey, fieldBounds, state, state.itemAt(index));
	}

	/**
	 * Selects the first suggestion whose label contains the given text,
	 * case-insensitively, scrolling the list if the match sits below the
	 * visible rows.
	 *
	 * Scrolling is needed in practice rather than defensively: the dropdown's
	 * viewport only fits about four rows, while the render tree also exposes
	 * the rows Flutter built ahead in its cache extent. For the query "cairo"
	 * the first match is index 5, which is built and readable but well below
	 * the visible band - tapping its unscrolled position would land under the
	 * dropdown entirely.
	 */
	protected void selectDropdownItemContaining(String fieldValueKey, Rectangle fieldBounds, String text) {
		String needle = text.toLowerCase();

		for (int attempt = 0; attempt <= MAX_DROPDOWN_SCROLLS; attempt++) {
			DropdownState state = awaitDropdown(dropdown -> dropdown.firstMatch(needle) != null,
					"an item containing '" + needle + "'");
			DropdownItem match = state.firstMatch(needle);

			if (state.isFullyVisible(match)) {
				selectItem(fieldValueKey, fieldBounds, state, match);
				return;
			}

			logger.info("'{}' sits {} logical px below the visible rows (scrollOffset {}); scrolling",
					match.label(), Math.round(state.scrollShortfall(match)),
					Math.round(state.scrollOffset()));
			scrollDropdown(fieldBounds, state);
		}

		throw new IllegalStateException("Could not bring an item containing '" + needle
				+ "' into view after " + MAX_DROPDOWN_SCROLLS + " scroll(s)");
	}

	/**
	 * Taps the row, which must already be within the visible band.
	 *
	 * Uses a raw W3C pointer tap rather than "mobile: clickGesture" - that
	 * extension command is UiAutomator2-only and errors out under XCUITest,
	 * whereas a plain pointer press/release (the same primitive
	 * {@link #scrollDropdown} already relies on) taps correctly on both
	 * drivers.
	 *
	 * No read-back verification after tapping - see {@link
	 * SignupSteps#enterPhone} for why: this app's custom-styled widgets don't
	 * expose their current value through the native accessibility tree,
	 * confirmed via a page source dump for this field's own class of widget.
	 */
	private void selectItem(String fieldValueKey, Rectangle fieldBounds, DropdownState state, DropdownItem item) {
		logger.info("Dropdown open with {} item(s); selecting index {}: '{}'", state.items().size(),
				item.index(), item.label());

		int x = fieldBounds.getX() + fieldBounds.getWidth() / 2;
		int y = dropdownTop(fieldBounds) + (int) Math.round(
				(state.visibleOffsetOf(item) + state.rowPitch() / 2) * state.devicePixelRatio());

		PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "dropdown-tap");
		Sequence tap = new Sequence(finger, 0)
				.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y))
				.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
				.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

		performNativeGesture(tap);
		logger.info("Tapped dropdown item '{}' at ({}, {})", item.label(), x, y);
	}

	/**
	 * Scrolls the open list down by roughly the given number of logical
	 * pixels. Deliberately approximate - the caller re-reads the render tree
	 * afterwards and recomputes from the new scrollOffset, so fling and
	 * momentum don't have to be predicted.
	 *
	 * Appium's "mobile: scrollGesture" was tried first and never moved this
	 * list at all - confirmed live: identical scrollOffset=0.0 across 6
	 * attempts with varying percent values, and a render tree dump mid-run
	 * showed a real scrollable panel (BoxConstraints h=242.2 wrapping its own
	 * gesture detector), not a disabled one. A raw `adb shell input swipe`
	 * over the same on-screen region, checked at the same paused moment, DID
	 * scroll it (revealed items down to the last one). So the panel accepts
	 * touch input; the "mobile: scrollGesture" abstraction just doesn't
	 * synthesize it in a way this widget's gesture arena responds to. A
	 * manual W3C pointer press/move/release - the same primitive `adb input
	 * swipe` ultimately dispatches - reproduces the working version.
	 */
	private void scrollDropdown(Rectangle fieldBounds, DropdownState state) {
		int x = fieldBounds.getX() + fieldBounds.getWidth() / 2;
		int height = (int) Math.round(state.viewportExtent() * state.devicePixelRatio());
		int top = dropdownTop(fieldBounds);
		int fromY = top + (int) Math.round(height * 0.85);
		int toY = top + (int) Math.round(height * 0.15);

		PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "dropdown-scroll");
		Sequence swipe = new Sequence(finger, 0)
				.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, fromY))
				.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
				.addAction(finger.createPointerMove(Duration.ofMillis(300), PointerInput.Origin.viewport(),
						x, toY))
				.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

		performNativeGesture(swipe);
		logger.info("Swiped ({},{}) -> ({},{})", x, fromY, x, toY);
	}

	/** Switches to the native context, plays the gesture, then switches back. */
	private void performNativeGesture(Sequence sequence) {
		driver.switchToContext("NATIVE_APP");
		try {
			driver.perform(List.of(sequence));
		} finally {
			driver.switchToContext("FLUTTER");
		}
	}

	/** The dropdown renders directly beneath its field. */
	private int dropdownTop(Rectangle fieldBounds) {
		return fieldBounds.getY() + fieldBounds.getHeight();
	}

	/**
	 * Reads a field's on-screen bounds from the native hierarchy. Must be
	 * called before the field's dropdown is opened: while a dropdown is open
	 * the field itself drops out of the accessibility tree, so the geometry
	 * has to be captured up front.
	 */
	protected Rectangle nativeBoundsOf(String fieldValueKey) {
		driver.switchToContext("NATIVE_APP");
		try {
			return driver.findElement(nativeLocator(fieldValueKey)).getRect();
		} finally {
			driver.switchToContext("FLUTTER");
		}
	}

	/** Whether a native element (e.g. a button) reports itself enabled. */
	private boolean nativeIsEnabled(String valueKey) {
		return awaitNativeElement(valueKey, WebElement::isEnabled);
	}

	/**
	 * Polls the render tree until the dropdown satisfies the given condition.
	 * The lists build asynchronously - the university one is API-backed - so
	 * an immediate read finds nothing.
	 */
	private DropdownState awaitDropdown(Predicate<DropdownState> condition, String description) {
		long deadline = System.currentTimeMillis() + DROPDOWN_TIMEOUT_MS;

		while (true) {
			DropdownState state = readDropdownState();
			if (condition.test(state)) {
				return state;
			}
			if (System.currentTimeMillis() >= deadline) {
				throw new IllegalStateException("Dropdown never showed " + description + " within "
						+ DROPDOWN_TIMEOUT_MS + "ms; it holds " + state.labels());
			}
			sleep(DROPDOWN_POLL_INTERVAL_MS);
		}
	}

	/**
	 * Reads the open dropdown's items and scroll geometry off the render
	 * tree.
	 *
	 * Suggestion rows are ListTiles, so their text renders under a creator
	 * chain containing _ListTile - that's what separates them from the
	 * screen's other text (field labels, buttons, the typed query itself).
	 * Within one render object the only quoted string is its actual text; the
	 * surrounding TextSpan lines are unquoted style attributes. Each row's
	 * position within the list comes from the enclosing IndexedSemantics'
	 * parentData, and the list's own scroll position from the nearest
	 * preceding SliverConstraints - taking the nearest one matters, since the
	 * page's outer ScrollView contributes constraints of its own.
	 *
	 * SliverConstraints wrap over several lines, so the block is joined and
	 * whitespace-collapsed before its fields are read.
	 */
	protected DropdownState readDropdownState() {
		String renderTree = driver.getRenderTree();

		List<DropdownItem> items = new ArrayList<>();
		String creatorChain = "";
		boolean readingCreator = false;
		StringBuilder sliverBlock = null;
		String currentSliver = null;
		String itemsSliver = null;
		int index = 0;
		double layoutOffset = 0;

		for (String line : renderTree.split("\n")) {
			if (line.contains(SLIVER_CONSTRAINTS_MARKER)) {
				sliverBlock = new StringBuilder(line);
			} else if (sliverBlock != null) {
				sliverBlock.append(' ').append(line);
				if (line.contains(SLIVER_CONSTRAINTS_END_MARKER)) {
					currentSliver = WHITESPACE_AND_TREE_GLYPHS.matcher(sliverBlock).replaceAll(" ");
					sliverBlock = null;
				}
			}

			Matcher position = ITEM_POSITION_PATTERN.matcher(line);
			if (position.find()) {
				index = Integer.parseInt(position.group(1));
				layoutOffset = Double.parseDouble(position.group(2));
			}

			if (line.contains("creator:")) {
				creatorChain = line;
				readingCreator = true;
			} else if (readingCreator) {
				if (line.contains("parentData:")) {
					readingCreator = false;
				} else {
					creatorChain += line;
				}
			}

			Matcher label = ITEM_LABEL_PATTERN.matcher(line);
			if (label.find() && creatorChain.contains(LIST_TILE_MARKER)) {
				items.add(new DropdownItem(index, layoutOffset, label.group(1)));
				if (itemsSliver == null) {
					itemsSliver = currentSliver;
				}
			}
		}

		return new DropdownState(items,
				readDouble(itemsSliver, SCROLL_OFFSET_PATTERN, 0),
				readDouble(itemsSliver, VIEWPORT_EXTENT_PATTERN, Double.MAX_VALUE),
				readDouble(renderTree, DEVICE_PIXEL_RATIO_PATTERN, 1),
				rowPitchOf(items));
	}

	/** Extracts the first group of {@code pattern} from {@code source} as a double, or {@code fallback} if absent/unmatched. */
	private static double readDouble(String source, Pattern pattern, double fallback) {
		if (source == null) {
			return fallback;
		}
		Matcher matcher = pattern.matcher(source);
		return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
	}

	/**
	 * Row-to-row spacing, taken from the items themselves so it doesn't
	 * assume a screen density. Falls back only when the list is too short to
	 * measure, where the exact value can't change which row gets tapped.
	 */
	private static double rowPitchOf(List<DropdownItem> items) {
		return items.size() < 2 ? FALLBACK_ROW_PITCH
				: items.get(1).layoutOffset() - items.get(0).layoutOffset();
	}

	/** One suggestion row: its position in the list and its rendered text. */
	protected record DropdownItem(int index, double layoutOffset, String label) {
	}

	/**
	 * The open dropdown: the rows Flutter has built, plus the geometry needed
	 * to work out where any of them currently sits on screen. Offsets and
	 * extents are Flutter logical pixels; multiply by devicePixelRatio for
	 * device pixels.
	 */
	protected record DropdownState(List<DropdownItem> items, double scrollOffset,
			double viewportExtent, double devicePixelRatio, double rowPitch) {

		/** Every currently-built row's label, in list order. */
		List<String> labels() {
			return items.stream().map(DropdownItem::label).toList();
		}

		/** The row at the given list index, or null if it hasn't been built yet. */
		DropdownItem itemAt(int index) {
			return items.stream().filter(item -> item.index() == index).findFirst().orElse(null);
		}

		/** The first built row whose label contains {@code lowercaseNeedle}, or null if none match (yet). */
		DropdownItem firstMatch(String lowercaseNeedle) {
			return items.stream()
					.filter(item -> item.label().toLowerCase().contains(lowercaseNeedle))
					.findFirst()
					.orElse(null);
		}

		/** Distance from the top of the visible band, in logical pixels. */
		double visibleOffsetOf(DropdownItem item) {
			return item.layoutOffset() - scrollOffset;
		}

		/** Whether the row's entire height sits within the visible viewport right now. */
		boolean isFullyVisible(DropdownItem item) {
			double offset = visibleOffsetOf(item);
			return offset >= 0 && offset + rowPitch <= viewportExtent;
		}

		/** How far the list must scroll down to reveal the row fully. */
		double scrollShortfall(DropdownItem item) {
			return visibleOffsetOf(item) + rowPitch - viewportExtent;
		}
	}

	/**
	 * Taps a field natively (see {@link BaseFlutterSteps#tapByKeyNative})
	 * before typing into it via the Flutter Driver's sendKeys - for search
	 * fields whose Flutter Driver click doesn't reliably trigger the app's
	 * own focus/open-dropdown handling (confirmed live: the typed text lands
	 * either way, but no suggestions dropdown opens unless the field is
	 * focused natively first).
	 *
	 * Returns the field's bounds captured right after the native tap
	 * (settled past any keyboard-open/scroll the tap triggers) rather than
	 * before it - {@link #selectDropdownItemContaining}'s coordinate tap
	 * missed every suggestion when bounds were captured pre-tap, since the
	 * field's actual on-screen position had already shifted by the time the
	 * dropdown opened. Bounds still have to be read before sendKeys, though:
	 * that's what opens the dropdown, and once it's open the field drops out
	 * of the accessibility tree (see {@link #nativeBoundsOf}).
	 */
	protected Rectangle tapNativeThenType(String valueKey, String value) {
		tapByKeyNative(valueKey);
		Rectangle bounds = nativeBoundsOf(valueKey);
		FlutterElement element = driver.findElement(FlutterBy.VALUE_KEY, valueKey);
		driver.waitFor(element, DEFAULT_WAIT_MS);
		element.sendKeys(value);
		logger.info("Entered value into {}", valueKey);
		return bounds;
	}

	/** Waits for a widget showing the exact given text (Flutter Driver's TEXT finder) and taps it. */
	protected void tapByText(String text) {
		FlutterElement element = driver.findElement(FlutterBy.TEXT, text);
		driver.waitFor(element, DEFAULT_WAIT_MS);
		element.click();
		logger.info("Tapped text {}", text);
	}

	/**
	 * Taps a button and confirms the tap actually took effect by waiting for
	 * the key expected to appear next, failing with a clear message instead
	 * of letting an unrelated later step time out with no clue why the
	 * screen never advanced.
	 */
	protected void tapAndAwaitNext(String key, String expectedNextFieldKey) {
		tapAndAwaitNext(() -> tapByKey(key), key, expectedNextFieldKey);
	}

	/**
	 * Same verification as {@link #tapAndAwaitNext(String, String)}, but
	 * parameterized by an arbitrary tap action rather than a plain
	 * {@link BaseFlutterSteps#tapByKey} call - lets {@link
	 * SignupSteps#tapSignupFromLanding()} reuse the same next-screen check
	 * around its startup-race-tolerant tap.
	 */
	protected void tapAndAwaitNext(Runnable tapAction, String key, String expectedNextFieldKey) {
		assertKeyAppears(tapAction, key, expectedNextFieldKey);
	}

	/**
	 * Runs {@code tapAction}, then asserts the given key appears afterward -
	 * shared by {@link #tapAndAwaitNext(Runnable, String, String)} (screen
	 * transitions) and the education-type card selections ({@link
	 * SignupSteps#universityEducation}, {@link SignupSteps#highschoolEducation},
	 * {@link SignupSteps#graduateEducation}), which reveal a field in place
	 * rather than navigating to a new screen. Either way, the failure
	 * message names exactly what was tapped and what was expected next, so a
	 * mistap here fails immediately instead of surfacing later as an
	 * unrelated timeout.
	 */
	protected void assertKeyAppears(Runnable tapAction, String tappedDescription, String expectedKey) {
		tapAction.run();

		FlutterElement next = driver.findElement(FlutterBy.VALUE_KEY, expectedKey);
		Assertions.assertDoesNotThrow(() -> driver.waitFor(next, DEFAULT_WAIT_MS),
				() -> "Tapped '" + tappedDescription + "' but '" + expectedKey
						+ "' never appeared - the tap may not have registered");
		logger.info("Verified {} appeared after tapping {}", expectedKey, tappedDescription);
	}

	/**
	 * Taps a button, tolerating the "No root widget is attached" race that
	 * can happen if this is the very first Flutter command sent right after
	 * the session was created: the driver can reach the app process before
	 * Flutter's runApp() has finished attaching its widget tree, so the
	 * Flutter Driver extension rejects the command outright. Only used for
	 * {@link SignupSteps#tapSignupFromLanding()} - every later tap follows a
	 * command that already succeeded, which rules out this race.
	 */
	protected void tapByKeyTolerantOfStartupRace(String valueKey) {
		long deadline = System.currentTimeMillis() + STARTUP_READY_TIMEOUT_MS;

		while (true) {
			try {
				tapByKey(valueKey);
				return;
			} catch (WebDriverException e) {
				String message = e.getMessage();
				boolean rootWidgetNotAttached = message != null && message.contains("No root widget is attached");
				if (!rootWidgetNotAttached || System.currentTimeMillis() >= deadline) {
					throw e;
				}
				logger.info("Flutter root widget not attached yet; retrying tap of {}", valueKey);
				sleep(DROPDOWN_POLL_INTERVAL_MS);
			}
		}
	}

	/**
	 * Polls the Continue button until it reports enabled, or fails naming
	 * the step just performed. Used as a proxy for "the value(s) just
	 * entered were accepted": these fields' native nodes don't expose their
	 * current text/selection at all (see {@link SignupSteps#enterPhone}'s
	 * javadoc), so a direct read-back assertion isn't possible, but Continue
	 * only enables once every field on the current screen is valid - unlike
	 * the fields' own text, enabled/disabled is a real native accessibility
	 * flag this app's widgets do set correctly.
	 *
	 * Continue only turns on once ALL of the current screen's fields are
	 * valid, not progressively as each one is filled - confirmed against
	 * the app - so this only pinpoints "something on this screen wasn't
	 * accepted," not which specific field. Call it once, after the last
	 * field of a screen, not after every individual field.
	 *
	 * @param afterStep short description of the step(s) just performed,
	 *                  used in the failure message
	 */
	protected void awaitContinueEnabled(String afterStep) {
		long deadline = System.currentTimeMillis() + DEFAULT_WAIT_MS;

		while (!nativeIsEnabled("signup_continue_button")) {
			if (System.currentTimeMillis() >= deadline) {
				Assertions.fail("signup_continue_button did not become enabled after " + afterStep
						+ " - a value may not have been accepted");
			}
			sleep(DROPDOWN_POLL_INTERVAL_MS);
		}
		logger.info("Verified signup_continue_button is enabled after {}", afterStep);
	}
}
