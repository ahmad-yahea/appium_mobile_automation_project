package automation.tests;

import automation.base.FlutterBaseTest;
import automation.clients.InvitationApiClient;
import automation.data.SignupTestData;
import automation.report.RetryingTest;
import automation.steps.ContentPieceSteps;
import automation.steps.JourneySteps;
import automation.steps.SignupSteps;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.WebDriverException;

/**
 * End-to-end journey for a "Highschool" user: signs up, then continues
 * past signup into the rest of the app. Driven through Appium Flutter
 * Driver instead of UiAutomator2 - see FlutterBaseTest for why this needs
 * its own driver/session.
 *
 * The signup portion replays the same step sequence as {@link
 * SignupHighschoolUserTypeTest} via the shared {@link SignupSteps}
 * step-object - see there for the ValueKey locator details and per-step
 * caveats - except the signup email comes from {@link InvitationApiClient},
 * which generates it as part of inviting a new premium user, rather than
 * from {@link SignupTestData}. A plain {@link SignupTestData} email signs
 * up as a free user, which the journey screens are gated behind. The
 * journey portion past signup uses {@link JourneySteps} for Daily
 * Intelligence and roadmap navigation, then {@link ContentPieceSteps} for
 * the "Impact of Self Awareness" lesson itself - see those classes'
 * javadoc for which of their ValueKeys are independently confirmed live
 * against this build versus sourced from the mobile team's flow doc.
 */
public class JourneyHighschoolTest extends FlutterBaseTest {

	private SignupSteps signupSteps;
	private JourneySteps journeySteps;
	private ContentPieceSteps impactOfSelfAwarenessSteps;
	private ContentPieceSteps whatShapesIdentitySteps;

	/** Creates fresh step-objects bound to this test's driver session before each run. */
	@BeforeEach
	public void initSteps() {
		signupSteps = new SignupSteps(driver, report);
		journeySteps = new JourneySteps(driver, report);
		impactOfSelfAwarenessSteps = new ContentPieceSteps(driver, report, "impact_of_self_awareness");
		whatShapesIdentitySteps = new ContentPieceSteps(driver, report, "what_shapes_your_identity");
	}

	/**
	 * Invites a premium highschool student, signs up as them, then drives
	 * into the post-signup journey as far as currently possible - see the
	 * inline comments below for exactly where the journey portion stops and
	 * why. Retried (fresh Appium session and a fresh invitation) up to 3
	 * times on a connection hangup - see {@code onExceptions}, which also
	 * covers {@link InvitationApiClient}'s Postman CLI call - never on a
	 * real step/assertion failure.
	 */
	@RetryingTest(maxAttempts = 3,
			onExceptions = {WebDriverException.class, IOException.class, UncheckedIOException.class})
	public void highschoolJourney() {
		String email = InvitationApiClient.inviteAndCaptureEmail();

		signupSteps.tapSignupFromLanding();

		// Screen 1: email, mobile number, password - the email was invited
		// above so the app grants it premium/journey access (see class javadoc).
		signupSteps.enterEmail(email);
		signupSteps.tapContinue("signup_phone_field");
		signupSteps.enterPhone(SignupTestData.uniquePhone());
		signupSteps.tapContinue("signup_password_field");
		signupSteps.enterPassword(SignupTestData.DEFAULT_PASSWORD);
		signupSteps.tapContinue("signup_first_name_field");

		// Screen 2: first name, last name, date of birth.
		signupSteps.firstName(SignupTestData.randomFirstName());
		signupSteps.lastName(SignupTestData.randomLastName());
		signupSteps.dateOfBirth(SignupTestData.uniqueDateOfBirth());
		signupSteps.tapContinue("signup_education_type_high_school");

		// Screen 3: highschool education details - the invited student's
		// school is fixed by the invitation, so the field is locked, not
		// typed into.
		signupSteps.highschoolEducation();
		signupSteps.assertSchoolNameLocked();
		signupSteps.curriculum("Egyptian National Curriculum");
		// signupSteps.grade(SignupTestData.GRADE);
		signupSteps.tapContinue();

		// Journey: this flow lands directly on the personality-intro screen
		// after signup - no intervening "content" screen (content_continue_button)
		// appears here, confirmed via a render-tree dump taken at the point
		// tapContentContinue() used to time out (see JourneySteps javadoc).
		journeySteps.tapPersonalityIntroMaybeLater();

		// Daily Intelligence: this invited account is premium, so the welcome
		// overlay is expected to be skipped straight to the reflection overlay -
		// see JourneySteps#startDailyIntelligenceIfWelcomeShown.
		// journeySteps.startDailyIntelligenceIfWelcomeShown();

		// Pick ONE of the two reflection paths below (comment out the other):
		// answer-and-submit reaches the roadmap via the success screen's
		// "Continue to journey"; close-without-answering skips straight to the
		// roadmap and hides reflection for the rest of the day (see
		// JourneySteps#closeDailyIntelligenceReflection).
		
		// journeySteps.answerDailyIntelligence("This is my reflection answer.");
		// journeySteps.submitDailyIntelligence();
		// journeySteps.continueToJourney();
		
		// journeySteps.closeDailyIntelligenceReflection();
		
		
		// Roadmap: Self Discovery stage -> Identity module -> Awareness of
		// Identity section -> "Impact of Self Awareness" lesson row. The
		// roadmap cards have no ValueKey on this build and the screen animates
		// continuously, so these steps navigate by visible label with frame
		// sync off - see JourneySteps#tapRoadmapLabel.
		journeySteps.awaitRoadmap();
		journeySteps.openSelfDiscoveryStage();
		journeySteps.openIdentityModule();
		journeySteps.openAwarenessOfIdentitySection();
		journeySteps.openImpactOfSelfAwarenessItem();

		// Lesson: intro -> skip video -> skip again on the outro -> quiz, then
		// answer all 7 quiz questions (4th option each), advancing after each.
		// See ContentPieceSteps for the per-screen locator caveats.
		impactOfSelfAwarenessSteps.tapStart();
		impactOfSelfAwarenessSteps.skipVideo();
		impactOfSelfAwarenessSteps.skipByLabel();      // outro "Skip" - its ValueKey resolves but tapping it hangs
		impactOfSelfAwarenessSteps.startQuiz();
		impactOfSelfAwarenessSteps.answerAllQuizQuestions();

		// Quiz done -> "Continue" on the XP screen chains into the next piece:
		// "What shapes your identity", an interactive identity infographic. That
		// Continue tap drops the native session half, so everything from here is
		// Flutter-context only - see ContentPieceSteps#continuePastQuizCompletion.
		impactOfSelfAwarenessSteps.continuePastQuizCompletion();
		whatShapesIdentitySteps.exploreNow();
	}
}