package automation.data;

import automation.config.Config;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal signup test data helpers - deliberately not a general "test data
 * factory". Grows alongside SignupSteps as more scenario tests are added;
 * if data generation needs get more elaborate than this, that's the signal
 * to revisit, not before.
 */
public final class SignupTestData {

	/** Plus-addressing base, e.g. {@code qa.user+<timestamp>@example.com}; override in .env. */
	private static final String EMAIL_LOCAL_PART = Config.get("TEST_EMAIL_LOCAL_PART", "qa.user");
	private static final String EMAIL_DOMAIN = Config.get("TEST_EMAIL_DOMAIN", "example.com");

	// Extension point: once a scenario needs a different password per run,
	// turn this into a parameter on a new/overloaded method below (e.g. a
	// validPassword()/weakPassword() pair for invalid-input variants)
	// rather than hardcoding at each call site.
	public static final String DEFAULT_PASSWORD = Config.get("TEST_PASSWORD", "Test1234!");

	// Any year before the current one yields a valid past date regardless
	// of the picker's default day/month (see SignupSteps.dateOfBirth). 2015
	// sits on the picker's default grid page (2008-2022, confirmed via
	// testing) so no scrolling is needed, and deliberately isn't the grid's
	// own default (2016) - the dialog's header chip always shows the
	// current year as text too, so a year matching that default makes
	// tapByText("2016") ambiguous (two "2016" widgets on screen at once).
	static final int DEFAULT_BIRTH_YEAR = 2015;

	/** Pool for {@link #randomFirstName()}. */
	private static final String[] FIRST_NAMES =
			{"Amir", "Chen", "Diana", "Farah", "Kenji", "Layla", "Marco", "Nadia", "Omar", "Sara"};

	/**
	 * Pool for {@link #randomLastName()} - each entry gets " Appium" appended,
	 * so the generated last name always identifies the account as test data.
	 */
	private static final String[] LAST_NAMES =
			{"Hassan", "Ibrahim", "Khalil", "Mansour", "Nasser", "Rahman", "Saleh", "Youssef"};

	/** Non-instantiable - every member is static. */
	private SignupTestData() {
	}

	/** A random first name, plain (no suffix). */
	public static String randomFirstName() {
		return FIRST_NAMES[ThreadLocalRandom.current().nextInt(FIRST_NAMES.length)];
	}

	/**
	 * A random last name with " Appium" always appended, so test-created
	 * accounts are identifiable as such regardless of which random name they
	 * got.
	 */
	public static String randomLastName() {
		return LAST_NAMES[ThreadLocalRandom.current().nextInt(LAST_NAMES.length)] + " Appium";
	}

	/**
	 * Unique email per run so reruns don't collide with an
	 * already-registered account.
	 */
	public static String uniqueEmail() {
		return EMAIL_LOCAL_PART + "+" + System.currentTimeMillis() + "@" + EMAIL_DOMAIN;
	}

	/**
	 * Valid Egyptian mobile network prefixes - the only ones the signup form
	 * accepts. Written without the domestic trunk "0": the field already has
	 * a separate +20 country-code selector, so the national significant
	 * number goes in starting with "1" (010 dialed domestically is "10"
	 * once the leading trunk digit is dropped for the +20-prefixed form).
	 */
	private static final String[] EGYPT_MOBILE_PREFIXES = {"10", "11", "12"};

	/**
	 * Unique 10-digit Egyptian mobile number per run so reruns don't collide
	 * with an already-registered account. SignupSteps.enterPhone explicitly
	 * selects Egypt as the phone field's country code before typing, so it
	 * only validates numbers shaped like a real Egyptian mobile number: one
	 * of the 10/11/12 network prefixes, followed by 8 more digits.
	 */
	public static String uniquePhone() {
		String prefix = EGYPT_MOBILE_PREFIXES[(int) (System.currentTimeMillis() % EGYPT_MOBILE_PREFIXES.length)];
		long last8Digits = System.currentTimeMillis() % 100_000_000L;
		return prefix + String.format("%08d", last8Digits);
	}

	/**
	 * The birth year to use for signup. Not actually randomized like
	 * {@link #uniqueEmail()}/{@link #uniquePhone()} - date of birth isn't
	 * part of what the backend uses to detect a duplicate account, so a
	 * fixed, picker-friendly year (see {@link #DEFAULT_BIRTH_YEAR}) is fine
	 * to reuse across runs.
	 */
	public static int uniqueDateOfBirth() {
		return DEFAULT_BIRTH_YEAR;
	}

	/** The grade to select on the highschool grade popup. */
	public static final int GRADE = 10;
}
