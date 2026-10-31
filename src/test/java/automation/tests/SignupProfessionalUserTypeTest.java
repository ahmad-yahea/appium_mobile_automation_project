package automation.tests;

import automation.base.FlutterBaseTest;
import automation.data.SignupTestData;
import automation.report.RetryingTest;
import automation.steps.SignupSteps;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.WebDriverException;

/**
 * Signup flow scenario for the "Professional" education type, driven
 * through Appium Flutter Driver instead of UiAutomator2 - see
 * FlutterBaseTest for why this needs its own driver/session.
 *
 * Screens 1 and 2 are identical to SignupUniversityUserTypeTest (see there
 * for the ValueKey locator details and per-step caveats).
 */
public class SignupProfessionalUserTypeTest extends FlutterBaseTest {

	private SignupSteps steps;

	/** Creates a fresh {@link SignupSteps} bound to this test's driver session before each run. */
	@BeforeEach
	public void initSteps() {
		steps = new SignupSteps(driver, report);
	}

	/**
	 * Walks the Professional signup path through screen 3 - incomplete per
	 * the class javadoc, since some of this screen's field ValueKeys aren't
	 * confirmed yet. Retried (fresh Appium session) up to 3 times on a
	 * connection hangup - see {@code onExceptions} - never on a real
	 * step/assertion failure.
	 */
	@RetryingTest(maxAttempts = 3, onExceptions = {WebDriverException.class, IOException.class})
	public void signupProfessionalType() {
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
		steps.tapContinue("signup_education_type_graduate");

		// Screen 3: professional education details (the rest of the signup form).
		steps.graduateEducation();
		steps.jobTitle("Software Engineer");
		steps.yearsExperience(3);
		steps.universityName("Cairo");
		steps.highestEducation();
		steps.graduateMajor();
		steps.companyName("automation");
		steps.tapContinue();
		threadSleep(3000);
	}
}
