package automation.steps;

import automation.base.FlutterBaseTest;
import automation.data.SignupTestData;
import automation.report.ScenarioReport;
import automation.report.StepExecutor;
import io.appium.java_client.AppiumBy;
import io.qualityplus.flutter.driver.AppiumFlutterDriver;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebElement;

/**
 * Page-object-style step methods for the automation signup flow, driven via
 * the Appium Flutter Driver (see {@link FlutterBaseTest} for why this flow
 * can't use UiAutomator2).
 *
 * The signup flow spans three screens, and the public step methods below
 * are grouped to match, in the order the app presents them:
 * <ol>
 *   <li>Screen 1 - email, mobile number, password</li>
 *   <li>Screen 2 - first name, last name, date of birth</li>
 *   <li>Screen 3 - education details (the rest of the form)</li>
 * </ol>
 * {@link #tapContinue()} advances from one screen to the next and is shared
 * by all three. Dropdown handling, render-tree parsing, and low-level
 * find/wait/tap helpers that support those steps but aren't signup steps in
 * their own right live in {@link SignupStepSupport}, this class's parent.
 *
 * Locators are Flutter ValueKeys from the automation id registry, not
 * accessibility ids/labels - do not use "Get Started" or "E-mail" here.
 *
 * Built up incrementally as each screen's field ids/types are confirmed.
 * Only add a named step method once its underlying key has been confirmed
 * against the app - don't invent key names ahead of that.
 */
public class SignupSteps extends SignupStepSupport {

	private final StepExecutor executor;

	/**
	 * @param driver the Flutter Driver session to drive signup screens through
	 * @param report the current scenario's step report - see {@link StepExecutor}
	 */
	public SignupSteps(AppiumFlutterDriver driver, ScenarioReport report) {
		super(driver);
		this.executor = new StepExecutor(driver, report);
	}

	// ------------------------------------------------------------------
	// Landing
	// ------------------------------------------------------------------

	/** Taps the "Sign up" button on the landing screen to start the flow. */
	public void tapSignupFromLanding() {
		executor.run("tapSignupFromLanding", () -> tapAndAwaitNext(
				() -> tapByKeyTolerantOfStartupRace("landing_signup_button"),
				"landing_signup_button", "signup_email_field"));
	}

	// ------------------------------------------------------------------
	// Screen 1 - email, mobile number, password
	// ------------------------------------------------------------------

	/** Types the given address into the email field on screen 1. */
	public void enterEmail(String email) {
		executor.run("enterEmail(" + email + ")", () -> waitAndType("signup_email_field", email));
	}

	/**
	 * Types the given number into the mobile number field on screen 1, after
	 * explicitly selecting the given country code - see {@link
	 * #selectCountryCode} for why the country has to be picked first rather
	 * than relying on the field's own auto-detect.
	 *
	 * Reading the field back to check whether it's holding the typed value
	 * isn't reliable here - confirmed via a native page source dump, this
	 * app's custom-styled widgets never expose their current value through
	 * the native accessibility tree at all (no populated "text" attribute,
	 * no merged content-desc/name/label beyond the static ValueKey), on any
	 * field, not just this one - see {@link SignupStepSupport#selectItem}
	 * (referenced via its callers) and {@link #universityEducation} for the
	 * same limitation confirmed against a button and a selection card. So
	 * instead of reading the value back, {@link
	 * SignupStepSupport#awaitContinueEnabled} is used as a proxy for
	 * "accepted".
	 */
	public void enterPhone(String phone) {
		executor.run("enterPhone(" + phone + ")", () -> {
			selectCountryCode("Egypt");
			waitAndType("signup_phone_field", phone);
			awaitContinueEnabled("entering phone number");
		});
	}

	/**
	 * Opens the phone field's country-code picker (ValueKey
	 * "country-code-chip", a Flutter CountryButton confirmed via a render-tree
	 * dump) and selects the given country, so the field already carries the
	 * right country code before the phone number is typed - avoiding the
	 * field's own async auto-detect, which resets the field back to empty a
	 * couple of seconds after typing and switches the selector out from
	 * under whatever was already entered. Retyping after that reset used to
	 * mean tapping into the now-empty field a second time, which - confirmed
	 * via a native page source dump - is what was surfacing a native "Paste"
	 * bubble: tapping an empty Flutter TextField with clipboard content shows
	 * that bubble, and the app's widgets don't expose enough state to detect
	 * and dodge it.
	 *
	 * The picker's own search box turned out not to be usable here: its
	 * native accessibility bounds wrongly span the whole dialog rather than
	 * just the input, so a plain tap/click lands on a list row instead and
	 * dismisses the picker - confirmed live across native taps, Flutter
	 * Driver taps, and even a repositioned coordinate tap. Once a tap was
	 * found that reliably keeps the dialog open, every way tried of typing
	 * into it (native sendKeys, raw `adb shell input text`, W3C Actions
	 * keyboard input) still left the field's own text empty and the list
	 * unfiltered - the widget never picks up real focus from any of them.
	 * So this scrolls the native list directly instead: same native list the
	 * search box would have filtered, just walked with a UiScrollable
	 * expression (same androidUIAutomator strategy the picker's own rows are
	 * otherwise addressed through) rather than typed into.
	 *
	 * @param countryName the country's label as shown in the picker list,
	 *                     e.g. "Egypt" - matched against each row's
	 *                     content-desc, which pairs the name with its dial
	 *                     code (e.g. "Egypt\n+ 20")
	 */
	private void selectCountryCode(String countryName) {
		tapByKey("country-code-chip");

		String scrollToCountry = "new UiScrollable(new UiSelector().scrollable(true))"
				+ ".scrollIntoView(new UiSelector().descriptionContains(\"" + countryName + "\"))";

		awaitNativeElement(AppiumBy.androidUIAutomator(scrollToCountry), (Function<WebElement, Void>) ignored -> null);
		awaitNativeElement(
				AppiumBy.xpath(String.format("//android.widget.Button[contains(@content-desc,'%s')]", countryName)),
				(Function<WebElement, Void>) element -> {
					element.click();
					return null;
				});
		logger.info("Selected country code for '{}'", countryName);
	}

	/** Types the given password into the password field on screen 1. */
	public void enterPassword(String password) {
		executor.run("enterPassword", () -> waitAndType("signup_password_field", password));
	}

	/**
	 * Taps the "Continue" button that advances from the current screen to
	 * the next one, and confirms the tap actually landed by waiting for the
	 * given key - the field/button expected to appear on whatever comes
	 * next - rather than just trusting the tap didn't throw.
	 *
	 * Shared across all three signup screens - they all use the same
	 * ValueKey - so each call site passes what it specifically expects to
	 * see next.
	 */
	public void tapContinue(String expectedNextFieldKey) {
		executor.run("tapContinue -> " + expectedNextFieldKey,
				() -> tapAndAwaitNext("signup_continue_button", expectedNextFieldKey));
	}

	/**
	 * Taps "Continue" with no next-screen check. Only for a transition whose
	 * destination isn't a confirmed ValueKey yet - see the class javadoc on
	 * not inventing key names ahead of confirmation. Currently only the tap
	 * out of screen 3, whose destination hasn't been walked yet.
	 */
	public void tapContinue() {
		executor.run("tapContinue", () -> tapByKey("signup_continue_button"));
	}

	// ------------------------------------------------------------------
	// Screen 2 - first name, last name, date of birth
	// ------------------------------------------------------------------

	/** Types the given value into the first name field on screen 2. */
	public void firstName(String firstName) {
		executor.run("firstName(" + firstName + ")", () -> waitAndType("signup_first_name_field", firstName));
	}

	/** Types the given value into the last name field on screen 2. */
	public void lastName(String lastName) {
		executor.run("lastName(" + lastName + ")", () -> waitAndType("signup_last_name_field", lastName));
	}

	/**
	 * Opens the date-of-birth picker by tapping the field, picks the given
	 * year, then a fixed day-of-month, then confirms. The month is left at
	 * whatever the dialog defaults to; only year and day are controlled
	 * here. Extending to a specific month would need the month header
	 * and/or prev/next-month arrows, which haven't been automated yet.
	 *
	 * No read-back verification after confirming - see {@link #enterPhone}
	 * for why: this app's custom-styled widgets don't expose their current
	 * value through the native accessibility tree.
	 *
	 * @param birthYear the year to select in the picker's year grid; must
	 *                  fall within the grid page the dialog opens on (see
	 *                  {@link SignupTestData#DEFAULT_BIRTH_YEAR} for why a
	 *                  particular year is chosen)
	 */
	public void dateOfBirth(int birthYear) {
		executor.run("dateOfBirth(" + birthYear + ")", () -> {
			tapByKey("signup_dob_field");
			tapByText(String.valueOf(birthYear));
			tapByText(String.valueOf(SAFE_DAY_OF_MONTH));
			tapByText(DATE_PICKER_CONFIRM_TEXT);
			logger.info("Confirmed date of birth for year {}", birthYear);
			awaitContinueEnabled("entering name/DOB");
		});
	}

	// ------------------------------------------------------------------
	// Screen 3 - education details (the rest of the signup form)
	// ------------------------------------------------------------------

	/**
	 * Selects "University" as the education type on screen 3.
	 *
	 * No selected-state verification: confirmed via a native page source
	 * dump that these education-type cards never set the native
	 * checked/selected accessibility flags on tap (both stay false
	 * regardless), so there's nothing reliable to assert against - the same
	 * class of widget limitation {@link #enterPhone} documents for reading
	 * back typed text. Instead, this asserts the field the selection reveals
	 * ("signup_university_name_field") actually appears, so a mistap fails
	 * here with a clear message rather than later inside an unrelated step.
	 */
	public void universityEducation() {
		executor.run("universityEducation", () -> assertKeyAppears(
				() -> tapByKey("signup_education_type_university"),
				"signup_education_type_university", "signup_university_name_field"));
	}

	/**
	 * Types the given text and selects the first suggestion that contains it.
	 *
	 * The suggestion list is a search result rather than a strict filter -
	 * typing "cairo" returns entries like "GBSB Global Business School" and
	 * "University of Wolverhampton" alongside the Cairo ones, and the Cairo
	 * ones aren't necessarily first. Matching on the typed text is what makes
	 * the selection correspond to what was asked for; if nothing matches, the
	 * step fails rather than settling for an unrelated institution.
	 *
	 * @param universityName text to type and to match against the returned
	 *                       suggestions (case-insensitive)
	 */
	public void universityName(String universityName) {
		executor.run("universityName(" + universityName + ")", () -> {
			Rectangle bounds = tapNativeThenType("signup_university_name_field", universityName.toLowerCase());
			selectDropdownItemContaining("signup_university_name_field", bounds, universityName);
		});
	}

	/**
	 * Opens the degree dropdown and selects the second entry (index 1).
	 * Not yet parameterized by a specific degree name - see
	 * {@link SignupStepSupport#selectDropdownItem} for why a fixed index is
	 * used instead of matching by label.
	 */
	public void degree() {
		executor.run("degree", () -> {
			Rectangle bounds = nativeBoundsOf("signup_degree_field");
			tapByKey("signup_degree_field");
			selectDropdownItem("signup_degree_field", bounds, 1);
		});
	}

	/**
	 * Opens the area-of-study dropdown and selects the second entry
	 * (index 1). Not yet parameterized by a specific area - see
	 * {@link SignupStepSupport#selectDropdownItem} for why a fixed index is
	 * used instead of matching by label.
	 */
	public void areaOfStudy() {
		executor.run("areaOfStudy", () -> {
			Rectangle bounds = nativeBoundsOf("signup_area_of_study_field");
			tapByKey("signup_area_of_study_field");
			selectDropdownItem("signup_area_of_study_field", bounds, 1);
			awaitContinueEnabled("completing university education details");
		});
	}

	/**
	 * Selects "Highschool" as the education type on screen 3.
	 *
	 * No selected-state verification - see {@link #universityEducation} for
	 * why (these cards never set native checked/selected flags on tap).
	 * Same reveal-field assertion as {@link #universityEducation}, here
	 * against "signup_school_name_field".
	 */
	public void highschoolEducation() {
		executor.run("highschoolEducation", () -> assertKeyAppears(
				() -> tapByKey("signup_education_type_high_school"),
				"signup_education_type_high_school", "signup_school_name_field"));
	}

	/**
	 * Selects "Graduate/Professional" as the education type on screen 3.
	 * Tapped natively (see {@link BaseFlutterSteps#tapByKeyNative}) rather
	 * than through the Flutter Driver - like the education-type cards this
	 * doesn't reliably register via a Flutter Driver click. Same
	 * reveal-field assertion as {@link #universityEducation}/{@link
	 * #highschoolEducation}, here against "signup_job_title_field".
	 */
	public void graduateEducation() {
		executor.run("graduateEducation", () -> assertKeyAppears(
				() -> tapByKeyNative("signup_education_type_graduate"),
				"signup_education_type_graduate", "signup_job_title_field"));
	}

	/**
	 * Types the given text and selects the first suggestion that contains it.
	 * Same search-and-match behavior as {@link #universityName} - see there
	 * for why matching on the typed text (rather than a fixed index) is used.
	 *
	 * @param schoolName text to type and to match against the returned
	 *                   suggestions (case-insensitive)
	 */
	public void schoolName(String schoolName) {
		executor.run("schoolName(" + schoolName + ")", () -> {
			Rectangle bounds = nativeBoundsOf("signup_school_name_field");
			waitAndType("signup_school_name_field", schoolName.toLowerCase());
			selectDropdownItemContaining("signup_school_name_field", bounds, schoolName);
		});
	}

	/**
	 * Asserts the highschool-name field is locked for an invited student -
	 * the highschool is fixed by the invitation (see InvitationApiClient),
	 * not chosen at signup.
	 *
	 * Confirmed live against the app that this can't be checked via
	 * {@code nativeIsEnabled}: a locked field still reports its native
	 * "enabled" accessibility flag as {@code true} (the lock is
	 * presentational - a dimmed, input-ignoring field, not a widget-level
	 * disabled state), so instead this attempts the same type-and-select
	 * interaction {@link #schoolName} uses and asserts no suggestion
	 * dropdown ever opens, which is what happens for a normal, unlocked
	 * field.
	 */
	public void assertSchoolNameLocked() {
		executor.run("assertSchoolNameLocked", () -> {
			waitAndType("signup_school_name_field", "zzz-should-not-match-any-school");

			long deadline = System.currentTimeMillis() + LOCKED_FIELD_CHECK_MS;
			do {
				Assertions.assertTrue(readDropdownState().items().isEmpty(),
						"Expected no suggestions dropdown to open for the locked highschool-name field");
				sleep(DROPDOWN_POLL_INTERVAL_MS);
			} while (System.currentTimeMillis() < deadline);
		});
	}

	/**
	 * Opens the curriculum dropdown, types the given text into its search
	 * box to filter, and taps the first option whose label contains it.
	 *
	 * Unlike {@link #universityName}/{@link #schoolName}, whose suggestion
	 * rows are Flutter widgets read off the render tree and tapped by
	 * computed coordinate, this dropdown's search box and option rows are
	 * plain native (`android.widget.EditText` / `android.widget.Button`)
	 * elements - confirmed via a native page source dump taken while the
	 * dropdown was open. The search box carries no resource-id/content-desc
	 * of its own, only a hint of "Select a curriculum"; each option button's
	 * content-desc is the curriculum's own label. Both are addressed and
	 * tapped directly through the native accessibility tree instead.
	 *
	 * @param curriculum text to type into the search box and to match
	 *                   against an option's content-desc (case-sensitive,
	 *                   matching the label's own casing)
	 */
	public void curriculum(String curriculum) {
		executor.run("curriculum(" + curriculum + ")", () -> {
			tapByKey("signup_curriculum_field");

			awaitNativeElement(AppiumBy.xpath("//android.widget.EditText[@hint='Select a curriculum']"),
					(Function<WebElement, Void>) element -> {
						element.click();
						element.sendKeys(curriculum);
						return null;
					});
			logger.info("Typed '{}' into curriculum search box", curriculum);

			awaitNativeElement(
					AppiumBy.xpath(String.format("//android.widget.Button[contains(@content-desc,'%s')]", curriculum)),
					(Function<WebElement, Void>) element -> {
						element.click();
						return null;
					});
			logger.info("Tapped curriculum option matching '{}'", curriculum);
		});
	}

	/**
	 * Opens the grade popup, types the given grade into its search box to
	 * filter, and taps the first option whose label contains it.
	 *
	 * Same shape as {@link #curriculum} - see there for why the search box
	 * and option rows are addressed as plain native elements rather than
	 * through the render tree.
	 *
	 * @param grade the grade to type into the search box and to match
	 *              against an option's content-desc
	 */
	public void grade(int grade) {
		executor.run("grade(" + grade + ")", () -> {
			tapByKey("signup_grade_field");

			String gradeText = String.valueOf(grade);

			awaitNativeElement(AppiumBy.xpath("//android.widget.EditText[@hint='Select a grade']"),
					(Function<WebElement, Void>) element -> {
						element.click();
						element.sendKeys(gradeText);
						return null;
					});
			logger.info("Typed '{}' into grade search box", gradeText);

			awaitNativeElement(
					AppiumBy.xpath(String.format("//android.widget.Button[contains(@content-desc,'%s')]", gradeText)),
					(Function<WebElement, Void>) element -> {
						element.click();
						return null;
					});
			logger.info("Tapped grade option matching '{}'", gradeText);
			awaitContinueEnabled("completing highschool education details");
		});
	}

	/**
	 * Opens the highest-education dropdown and selects the second entry
	 * (index 1). Not yet parameterized by a specific education level - see
	 * {@link SignupStepSupport#selectDropdownItem} for why a fixed index is
	 * used instead of matching by label.
	 */
	public void highestEducation() {
		executor.run("highestEducation", () -> {
			Rectangle bounds = nativeBoundsOf("signup_highest_education_field");
			tapByKey("signup_highest_education_field");
			selectDropdownItem("signup_highest_education_field", bounds, 1);
		});
	}

	/**
	 * Opens the graduate-major dropdown and selects the second entry
	 * (index 1). Same fixed-index approach as {@link #highestEducation} -
	 * see {@link SignupStepSupport#selectDropdownItem} for why.
	 */
	public void graduateMajor() {
		executor.run("graduateMajor", () -> {
			Rectangle bounds = nativeBoundsOf("signup_graduate_major_field");
			tapByKey("signup_graduate_major_field");
			selectDropdownItem("signup_graduate_major_field", bounds, 1);
		});
	}

	/** Types the given value into the job title field on screen 3. */
	public void jobTitle(String jobTitle) {
		executor.run("jobTitle(" + jobTitle + ")", () -> waitAndType("signup_job_title_field", jobTitle));
	}

	/**
	 * Types the given value into the company name field on screen 3. Last
	 * field entered on this screen, so this also confirms Continue enables
	 * afterward.
	 */
	public void companyName(String companyName) {
		executor.run("companyName(" + companyName + ")", () -> {
			waitAndType("signup_company_name_field", companyName);
			awaitContinueEnabled("completing professional education details");
		});
	}

	/** Types the given years of experience into the field on screen 3. */
	public void yearsExperience(int years) {
		executor.run("yearsExperience(" + years + ")",
				() -> waitAndType("signup_years_experience_field", String.valueOf(years)));
	}
}
