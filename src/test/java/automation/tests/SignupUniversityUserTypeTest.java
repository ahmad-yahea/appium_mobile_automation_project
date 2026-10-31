package automation.tests;

import automation.base.FlutterBaseTest;
import automation.data.SignupTestData;
import automation.report.RetryingTest;
import automation.steps.SignupSteps;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.WebDriverException;

/**
 * Signup flow scenario for the "University" education type, driven through
 * Appium Flutter Driver instead of UiAutomator2 - see FlutterBaseTest for
 * why this needs its own driver/session.
 *
 * A plain, ordered walkthrough of the app's three signup screens, grouped
 * below in the order they're presented to the user. Each screen transition
 * (tapContinue) verifies the next screen actually appeared; individual
 * field entry/selection steps don't verify their own effect beyond the tap
 * or type not throwing, since this app's widgets don't expose their
 * current value through the native accessibility tree (see SignupSteps,
 * e.g. enterPhone's javadoc, for why). Built up incrementally, step by
 * step, as each screen's field ids/types are confirmed. See SignupSteps
 * for the ValueKey locator details and per-step caveats.
 */
public class SignupUniversityUserTypeTest extends FlutterBaseTest {

	private SignupSteps steps;

	/** Creates a fresh {@link SignupSteps} bound to this test's driver session before each run. */
	@BeforeEach
	public void initSteps() {
		steps = new SignupSteps(driver, report);
	}

	/**
	 * Walks all three signup screens end to end for the University education
	 * type. Retried (fresh Appium session) up to 3 times on a connection
	 * hangup - see {@code onExceptions} - never on a real step/assertion
	 * failure.
	 */
	@RetryingTest(maxAttempts = 3, onExceptions = {WebDriverException.class, IOException.class})
	public void signupUniversityUserType() {
		steps.tapSignupFromLanding();

		// Screen 1: email, mobile number, password.
		steps.enterEmail(SignupTestData.uniqueEmail());
		steps.tapContinue("signup_phone_field");
		steps.enterPhone(SignupTestData.uniquePhone());
		steps.tapContinue("signup_password_field");
		steps.enterPassword(SignupTestData.DEFAULT_PASSWORD);
		steps.tapContinue("signup_first_name_field");

		// Screen 2: first name, last name, date of birth.
		steps.firstName(SignupTestData.randomFirstName());
		steps.lastName(SignupTestData.randomLastName());
		steps.dateOfBirth(SignupTestData.uniqueDateOfBirth());
		steps.tapContinue("signup_education_type_university");

		// Screen 3: education details (the rest of the signup form).
		steps.universityEducation();
		steps.universityName("Cairo");
		steps.degree();
		steps.areaOfStudy();
		steps.tapContinue();
	}
}