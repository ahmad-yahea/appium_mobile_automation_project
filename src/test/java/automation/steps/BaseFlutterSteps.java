package automation.steps;

import io.appium.java_client.AppiumBy;
import io.qualityplus.flutter.common.FlutterBy;
import io.qualityplus.flutter.driver.AppiumFlutterDriver;
import io.qualityplus.flutter.driver.FlutterElement;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared driver/tapByKey plumbing for the Flutter-driven step classes
 * ({@link SignupSteps}, {@link JourneySteps}) - both drove an identical
 * driver field, DEFAULT_WAIT_MS constant, logger, and tapByKey
 * implementation independently before this was pulled out.
 */
public abstract class BaseFlutterSteps {

	/** Default explicit-wait timeout for step elements to appear. */
	protected static final int DEFAULT_WAIT_MS = 10_000;

	/** Poll interval used while retrying a native-hierarchy lookup - see {@link #awaitNativeElement}. */
	private static final int NATIVE_POLL_INTERVAL_MS = 400;

	protected final AppiumFlutterDriver driver;
	protected final Logger logger = LoggerFactory.getLogger(getClass());

	/** @param driver the Flutter Driver session to drive screens through. */
	protected BaseFlutterSteps(AppiumFlutterDriver driver) {
		this.driver = driver;
	}

	/** Generic wait-then-tap helper for a confirmed ValueKey. */
	public void tapByKey(String valueKey) {
		FlutterElement element = driver.findElement(FlutterBy.VALUE_KEY, valueKey);
		driver.waitFor(element, DEFAULT_WAIT_MS);
		element.click();
		logger.info("Tapped {}", valueKey);
	}

	/**
	 * Taps a ValueKey if it appears within {@code timeoutMs}, and does
	 * nothing (no exception) if it doesn't - for a screen/overlay that's
	 * only sometimes shown, e.g. the Daily Intelligence welcome overlay,
	 * which premium/returning users skip straight past.
	 *
	 * @return whether the element appeared and was tapped
	 */
	public boolean tapByKeyIfPresent(String valueKey, int timeoutMs) {
		FlutterElement element = driver.findElement(FlutterBy.VALUE_KEY, valueKey);
		try {
			driver.waitFor(element, timeoutMs);
		} catch (WebDriverException e) {
			logger.info("{} did not appear within {}ms; skipping", valueKey, timeoutMs);
			return false;
		}
		element.click();
		logger.info("Tapped {}", valueKey);
		return true;
	}

	/** Waits for the field at {@code valueKey}, taps to focus it, then types {@code value} via the Flutter Driver. */
	protected void waitAndType(String valueKey, String value) {
		FlutterElement element = driver.findElement(FlutterBy.VALUE_KEY, valueKey);
		driver.waitFor(element, DEFAULT_WAIT_MS);
		element.click();
		logger.info("Focused {}", valueKey);
		element.sendKeys(value);
		logger.info("Entered value into {}", valueKey);
	}

	/**
	 * Taps a widget by its ValueKey through the native accessibility tree
	 * (plain Appium/UiAutomator2, via {@link #nativeLocator}) instead of the
	 * Flutter Driver's VALUE_KEY finder - for widgets where a Flutter Driver
	 * click doesn't reliably register, e.g. {@code SignupSteps}'
	 * education-type cards, and the Daily Intelligence reflection overlay's
	 * close button.
	 */
	protected void tapByKeyNative(String valueKey) {
		awaitNativeElement(valueKey, (Function<WebElement, Void>) element -> {
			element.click();
			return null;
		});
		logger.info("Tapped {} via native context", valueKey);
	}

	/**
	 * Locates a widget in the native hierarchy by its ValueKey. Uses a
	 * "contains" match against every accessibility-identifying attribute
	 * either platform's page source exposes - Android's content-desc, and
	 * iOS's name/label - rather than plain accessibilityId's exact match.
	 *
	 * Confirmed via a page source dump: a widget that shows its own visible
	 * text (e.g. the "Continue" button) exports content-desc as the key and
	 * the label joined with a newline - "signup_continue_button\nContinue" -
	 * not the bare key, so an exact-match accessibilityId lookup finds
	 * plain fields (nothing else in their content-desc) but not buttons.
	 * "contains" matches both shapes without needing to special-case either,
	 * and without assuming which shape iOS's tree uses.
	 */
	protected By nativeLocator(String valueKey) {
		return AppiumBy.xpath(String.format(
				"//*[contains(@content-desc,'%1$s') or contains(@name,'%1$s') or contains(@label,'%1$s')]",
				valueKey));
	}

	/**
	 * Reads a property off an element in the native hierarchy, retrying
	 * briefly - a widget can be excluded from the tree for a moment right
	 * after a dropdown/dialog above it finishes closing.
	 */
	protected <T> T awaitNativeElement(String valueKey, Function<WebElement, T> reader) {
		return awaitNativeElement(nativeLocator(valueKey), reader);
	}

	/**
	 * Same retry behavior as {@link #awaitNativeElement(String, Function)},
	 * but against an arbitrary native locator rather than one built from a
	 * ValueKey - for native elements that don't carry a Flutter ValueKey at
	 * all, e.g. {@code SignupSteps#curriculum}'s search box and option
	 * buttons.
	 */
	protected <T> T awaitNativeElement(By locator, Function<WebElement, T> reader) {
		driver.switchToContext("NATIVE_APP");
		try {
			long deadline = System.currentTimeMillis() + DEFAULT_WAIT_MS;
			while (true) {
				try {
					return reader.apply(driver.findElement(locator));
				} catch (NoSuchElementException e) {
					if (System.currentTimeMillis() >= deadline) {
						throw e;
					}
					sleep(NATIVE_POLL_INTERVAL_MS);
				}
			}
		} finally {
			driver.switchToContext("FLUTTER");
		}
	}

	/**
	 * Logs and returns the native accessibility tree at this exact moment -
	 * for diagnosing a tap that isn't registering, e.g. whether the target
	 * element's node exists/is covered and whether the keyboard is up.
	 */
	protected String dumpNativePageSource() {
		driver.switchToContext("NATIVE_APP");
		try {
			String source = driver.getPageSource();
			logger.info("Native page source:\n{}", source);
			return source;
		} finally {
			driver.switchToContext("FLUTTER");
		}
	}

	/**
	 * Logs and returns the Flutter render tree at this exact moment - a
	 * counterpart to {@link #dumpNativePageSource()} for diagnosing a
	 * ValueKey lookup that times out: shows which screen is active and the
	 * widget/creator chains currently built. Same source {@code StepExecutor}
	 * and {@code SignupStepSupport#readDropdownState} already read.
	 */
	protected String dumpRenderTree() {
		String renderTree = driver.getRenderTree();
		logger.info("Flutter render tree:\n{}", renderTree);
		return renderTree;
	}

	/**
	 * Captures a screenshot into {@code target/diag-<tag>-<timestamp>.png} and
	 * returns its path - a visual pair for the render-tree / page-source dumps
	 * when reporting an unresponsive or unkeyed widget to the mobile team.
	 */
	protected Path saveScreenshot(String tag) {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
		Path target = Paths.get(System.getProperty("user.dir"), "target", "diag-" + tag + "-" + stamp + ".png");
		// The screenshot endpoint isn't wired in the FLUTTER context on this
		// driver build ("Cannot read properties of undefined (reading
		// 'screenshot')") - take it from NATIVE_APP and switch back.
		driver.switchToContext("NATIVE_APP");
		try {
			File shot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
			Files.createDirectories(target.getParent());
			Files.copy(shot.toPath(), target);
			logger.info("Saved screenshot to {}", target);
		} catch (Exception e) {
			logger.warn("Could not save screenshot '{}': {}", tag, e.toString());
		} finally {
			driver.switchToContext("FLUTTER");
		}
		return target;
	}

	/**
	 * Toggles the Flutter Driver extension's frame sync. With it on (the
	 * default), every finder command first waits for the scheduler to go idle
	 * - which never happens on a screen with a perpetual animation, so the
	 * command times out. Turning it off lets finders run against the current
	 * frame. Best-effort: logs and continues if the driver build doesn't
	 * expose {@code flutter:setFrameSync}.
	 */
	protected void setFrameSync(boolean enabled) {
		try {
			driver.executeScript("flutter:setFrameSync", enabled, 5_000);
			logger.info("flutter:setFrameSync({}) OK", enabled);
		} catch (WebDriverException e) {
			logger.warn("flutter:setFrameSync({}) failed: {}", enabled, e.getMessage());
		}
	}

	/**
	 * Runs {@code action} with the Flutter Driver's frame sync turned off, then
	 * restores it - even if {@code action} throws. For screens with a
	 * perpetual animation (e.g. the Journey roadmap's pulsing node / animated
	 * path / Lottie), where a frame-synced {@code waitFor}/{@code tap} would
	 * otherwise stall until timeout - see {@link #setFrameSync}.
	 */
	protected void withFrameSyncOff(Runnable action) {
		setFrameSync(false);
		try {
			action.run();
		} finally {
			setFrameSync(true);
		}
	}

	/**
	 * Polls the {@code ByText} finder for up to {@code timeoutMs} and returns
	 * whether a widget showing exactly {@code text} ever appeared. Frame
	 * synced, so on a screen with a perpetual animation call it inside
	 * {@link #withFrameSyncOff}.
	 */
	protected boolean awaitText(String text, int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		FlutterElement element = driver.findElement(FlutterBy.TEXT, text);
		while (System.currentTimeMillis() < deadline) {
			try {
				driver.waitFor(element, 1_000);
				return true;
			} catch (WebDriverException e) {
				sleep(500);
			}
		}
		return false;
	}

	/**
	 * Polls the {@code ByText} finder for up to {@code timeoutMs} and returns
	 * whether a widget showing exactly {@code text} has <em>stopped</em> being
	 * found - i.e. it was removed or pushed offstage (the finder skips offstage
	 * widgets by default). The counterpart to {@link #awaitText}; useful on a
	 * {@code PageView}-style screen where the old page lingers in the render
	 * tree but the finder no longer resolves it once it scrolls offstage.
	 */
	protected boolean awaitTextGone(String text, int timeoutMs) {
		FlutterElement element = driver.findElement(FlutterBy.TEXT, text);
		long deadline = System.currentTimeMillis() + timeoutMs;
		do {
			try {
				driver.waitFor(element, 1_000);
			} catch (WebDriverException e) {
				return true;
			}
			sleep(500);
		} while (System.currentTimeMillis() < deadline);
		return false;
	}

	/**
	 * Waits for a widget showing exactly {@code visibleText} and taps it.
	 * A fallback locator for screens whose tap targets carry no ValueKey -
	 * currently the Journey roadmap cards on the staging build (see {@link
	 * JourneySteps} javadoc). Call inside {@link #withFrameSyncOff} on
	 * animated screens.
	 */
	protected void tapVisibleText(String visibleText) {
		FlutterElement element = driver.findElement(FlutterBy.TEXT, visibleText);
		driver.waitFor(element, DEFAULT_WAIT_MS);
		element.click();
		logger.info("Tapped visible text '{}'", visibleText);
	}

	/**
	 * Waits for and taps a widget whose visible text matches {@code visibleText}
	 * regardless of case. The Flutter client's {@link FlutterBy#TEXT} finder is
	 * exact-match only (and its {@code SEMANTICS_LABEL} finder hard-codes
	 * {@code isRegExp=false}), so this polls a small set of casings - the string
	 * as given, all-lower, all-UPPER, Capitalized, and Title Case - and taps the
	 * first that appears. For labels whose casing isn't guaranteed by the design
	 * (e.g. a "Skip" / "skip" button that differs screen to screen). Call inside
	 * {@link #withFrameSyncOff} on animated screens.
	 */
	protected void tapVisibleTextIgnoreCase(String visibleText) {
		tapVisibleTextIgnoreCase(visibleText, DEFAULT_WAIT_MS);
	}

	/**
	 * {@link #tapVisibleTextIgnoreCase(String)} with an explicit timeout, for
	 * probing a candidate label that may not be on screen at all - use a short
	 * timeout when trying several possible labels in turn.
	 */
	protected void tapVisibleTextIgnoreCase(String visibleText, int timeoutMs) {
		List<String> variants = caseVariants(visibleText);
		long deadline = System.currentTimeMillis() + timeoutMs;
		do {
			for (String variant : variants) {
				FlutterElement element = driver.findElement(FlutterBy.TEXT, variant);
				try {
					driver.waitFor(element, 1_000);
				} catch (WebDriverException e) {
					continue;
				}
				element.click();
				logger.info("Tapped visible text '{}' (case-insensitive match for '{}')", variant, visibleText);
				return;
			}
		} while (System.currentTimeMillis() < deadline);
		throw new NoSuchElementException(
				"No widget with visible text '" + visibleText + "' (any casing) appeared within "
						+ timeoutMs + "ms");
	}

	/**
	 * Case-insensitive visible-text tap that returns {@code false} instead of
	 * throwing when nothing matched within {@code timeoutMs} - for walking a
	 * list of candidate button labels where only one is expected to be present.
	 *
	 * @return whether a matching widget was found and tapped
	 */
	protected boolean tapVisibleTextIgnoreCaseIfPresent(String visibleText, int timeoutMs) {
		try {
			tapVisibleTextIgnoreCase(visibleText, timeoutMs);
			return true;
		} catch (NoSuchElementException e) {
			return false;
		}
	}

	/** Ordered, de-duplicated casings of {@code text} tried by {@link #tapVisibleTextIgnoreCase}. */
	private static List<String> caseVariants(String text) {
		LinkedHashSet<String> variants = new LinkedHashSet<>();
		variants.add(text);
		variants.add(text.toLowerCase());
		variants.add(text.toUpperCase());
		if (!text.isEmpty()) {
			variants.add(Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase());
		}
		StringBuilder titleCase = new StringBuilder(text.length());
		boolean atWordStart = true;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			titleCase.append(atWordStart ? Character.toUpperCase(c) : Character.toLowerCase(c));
			atWordStart = Character.isWhitespace(c);
		}
		variants.add(titleCase.toString());
		return new ArrayList<>(variants);
	}

	/** Blocks the current thread for {@code millis}, wrapping the checked InterruptedException. */
	protected void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting", e);
		}
	}
}
