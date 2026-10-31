package automation.base;

import automation.config.Config;
import automation.report.ScenarioReport;
import automation.report.ScenarioReportExtension;
import io.appium.java_client.service.local.AppiumDriverLocalService;
import io.appium.java_client.service.local.AppiumServiceBuilder;
import io.qualityplus.flutter.driver.AppiumFlutterDriver;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.remote.DesiredCapabilities;

/**
 * Base class for tests that need to type into Flutter text fields.
 *
 * UiAutomator2 can locate and tap these fields, but cannot type into them:
 * they are custom-rendered Flutter widgets with excludeSemantics=true, so
 * sendKeys/mobile:type/adb input text all reach a dead end (confirmed by
 * screenshot during debugging - field stays visually empty and Continue
 * stays disabled no matter which UiAutomator2 input method is used). Typing
 * requires the Flutter Driver protocol talking to a build with the Flutter
 * Driver extension enabled.
 *
 * Uses the staging APK at src/test/resources/app-staging-release.apk on
 * Android, or the staging .app at src/test/resources/app-staging-release.app
 * on iOS. Platform defaults to Android; pass -Dplatform=ios (and optionally
 * -Ddevice=<simulator name>) to run against the iOS Simulator instead.
 */
@ExtendWith(ScenarioReportExtension.class)
public class FlutterBaseTest {

	/** Absolute path to the Android staging build under test - see class javadoc. */
	protected static final String AUTOMATION_APK_PATH = Config.get("APP_APK_PATH",
			System.getProperty("user.dir") + "/src/test/resources/app-staging-release.apk");
	/** Absolute path to the iOS Simulator staging build under test - see class javadoc. */
	protected static final String AUTOMATION_APP_PATH_IOS = Config.get("APP_IOS_PATH",
			System.getProperty("user.dir") + "/src/test/resources/app-staging-release.app");

	/** The locally-started Appium server backing {@link #driver}; torn down in {@link #stopServer()}. */
	protected AppiumDriverLocalService service;
	/** The Flutter Driver session subclasses use to interact with the app. */
	protected AppiumFlutterDriver driver;
	/**
	 * This scenario's step-by-step outcome, populated via a
	 * {@link automation.report.StepExecutor} that subclasses build around
	 * {@link #driver}. Fresh per test instance (JUnit's default per-method
	 * lifecycle), including per {@code @RetryingTest} attempt.
	 */
	protected final ScenarioReport report = new ScenarioReport();

	/** @return this scenario's step report, read by {@link ScenarioReportExtension} once the test finishes. */
	public ScenarioReport report() {
		return report;
	}

	/**
	 * Starts a fresh local Appium server and Flutter Driver session before
	 * each test method, so tests don't share driver/app state with each
	 * other. Builds Android or iOS capabilities depending on the
	 * {@code platform} system property (defaults to Android).
	 */
	@BeforeEach
	public void startServer() {
		AppiumServiceBuilder builder = new AppiumServiceBuilder()
				.usingAnyFreePort()
				.withAppiumJS(resolveAppiumMainScript());

		service = AppiumDriverLocalService.buildService(builder);
		service.start();

		DesiredCapabilities caps = new DesiredCapabilities();
		boolean ios = "ios".equalsIgnoreCase(Config.get("platform", Config.get("PLATFORM", "android")));
		if (ios) {
			caps.setCapability("platformName", "IOS");
			caps.setCapability("appium:automationName", "Flutter");
			caps.setCapability("appium:deviceName", Config.get("device", Config.get("IOS_DEVICE", "iPhone 15")));
			caps.setCapability("appium:app", AUTOMATION_APP_PATH_IOS);
		} else {
			int fork = forkIndex();
			String serial = resolveAndroidSerial(fork);
			caps.setCapability("platformName", "ANDROID");
			caps.setCapability("appium:automationName", "Flutter");
			// udid is the reliable device selector when more than one emulator
			// is attached (a parallel run); deviceName alone lets Appium fall
			// back to "first ADB device". systemPort keeps concurrent
			// UiAutomator2 bridges off each other's default 8200.
			caps.setCapability("appium:udid", serial);
			caps.setCapability("appium:deviceName", serial);
			caps.setCapability("appium:systemPort", 8200 + fork);
			caps.setCapability("appium:app", AUTOMATION_APK_PATH);
			caps.setCapability("appium:autoGrantPermissions", true);
			// Re-push this APK on every session even when its versionCode is
			// unchanged, so the emulator can never run a stale build left over
			// from a previous APK.
			caps.setCapability("appium:enforceAppInstall", true);
			// This app's cold start on the local emulator regularly exceeds
			// Appium's 20s default, failing 'adb shell am start-activity'
			// before the session is even created.
			caps.setCapability("appium:adbExecTimeout", 60_000);
		}

		driver = new AppiumFlutterDriver(service.getUrl(), caps);
	}

	/** Quits the driver session and stops the local Appium server after each test method. */
	@AfterEach
	public void stopServer() {
		if (driver != null) {
			driver.quit();
		}
		if (service != null) {
			service.stop();
		}
	}

	/**
	 * This JVM's 1-based slot number, used to pick an emulator and a distinct
	 * {@code systemPort}. Defaults to the Surefire fork number (so a
	 * {@code -Dtest.forkCount=2} run spreads across slots 1 and 2 on its own),
	 * overridable with {@code -Dfork.index=<n>} for the case of launching one
	 * {@code mvn} per emulator by hand - give each invocation the same
	 * {@code -Dandroid.devices} list but a different {@code -Dfork.index}.
	 */
	private static int forkIndex() {
		return Integer.getInteger("fork.index", Integer.getInteger("surefire.forkNumber", 1));
	}

	/**
	 * Resolves which emulator this JVM targets. {@code -Dandroid.devices} is a
	 * comma-separated serial list (default {@code emulator-5554}); the given
	 * 1-based slot picks one, wrapping if there are more slots than serials. A
	 * plain single-fork {@code mvn test} always resolves to the first (and
	 * usually only) serial.
	 */
	private static String resolveAndroidSerial(int slot) {
		String[] serials = Config.get("android.devices", Config.get("ANDROID_DEVICES", "emulator-5554")).split("\\s*,\\s*");
		return serials[Math.floorMod(slot - 1, serials.length)].trim();
	}

	/** Blocks for the given duration, without forcing callers to handle InterruptedException. */
	protected static void threadSleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while sleeping", e);
		}
	}

	/**
	 * Resolves Appium's server entry script (main.js) without relying on
	 * java-client's built-in auto-detection, which locates it via
	 * `npm root -g` and only finds npm-global installs. Installs done
	 * through other means (e.g. Homebrew) place the package elsewhere,
	 * so that lookup fails even though Appium is installed and on PATH.
	 *
	 * Instead this resolves the real (symlink-resolved) location of the
	 * "appium" executable on PATH and looks for build/lib/main.js next
	 * to it - the layout is the same across install methods, only the
	 * package root differs.
	 */
	private static File resolveAppiumMainScript() {
		String override = Config.get("appium.main.js", Config.get("APPIUM_MAIN_JS", null));
		if (override != null && !override.isBlank()) {
			File overrideFile = new File(override);
			if (!overrideFile.isFile()) {
				throw new IllegalStateException(
						"APPIUM_MAIN_JS/appium.main.js points to a nonexistent file: " + override);
			}
			return overrideFile;
		}

		File packageRoot = resolveAppiumPackageRoot();
		if (packageRoot != null) {
			File mainScript = new File(packageRoot, "build/lib/main.js");
			if (mainScript.isFile()) {
				return mainScript;
			}
		}

		throw new IllegalStateException(
				"Could not locate Appium's main.js server script. Install Appium and ensure it is on PATH "
						+ "(`npm install -g appium` or `brew install appium`), or set the APPIUM_MAIN_JS "
						+ "environment variable (or -Dappium.main.js=...) to its location.");
	}

	/**
	 * Resolves the directory containing the "appium" executable on PATH
	 * (symlinks followed), so {@link #resolveAppiumMainScript()} can look
	 * for build/lib/main.js next to it. Returns null (rather than throwing)
	 * on any lookup failure, leaving the caller to produce one clear
	 * actionable error instead of a confusing one from here.
	 */
	private static File resolveAppiumPackageRoot() {
		try {
			Process process = new ProcessBuilder("sh", "-c", "command -v appium").start();
			String resolvedPath;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				resolvedPath = reader.readLine();
			}
			process.waitFor();

			if (resolvedPath == null || resolvedPath.isBlank()) {
				return null;
			}

			File entryPoint = new File(resolvedPath.trim()).getCanonicalFile();
			return entryPoint.getParentFile();
		} catch (IOException | InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
