package automation.clients;

import automation.config.Config;
import automation.data.SignupTestData;
import automation.tests.JourneyHighschoolTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invites a new user via the "invitation" Postman collection (a GraphQL
 * mutation) and returns the email it generated, so that signing up with
 * that same email in the app is recognized as premium and can reach the
 * post-signup journey - a plain {@link SignupTestData} email signs up as a
 * free user, which the journey screens are gated behind.
 *
 * The mutation's GraphQL input type is {@code HighSchoolStudentInvitationInput}
 * and the invite is sent under a highschool's own login - this is
 * highschool-specific, not a general premium-signup mechanism. Only {@link
 * JourneyHighschoolTest} should call this; {@code JourneyUniversityTest}
 * (not yet written) signs up with a plain {@link SignupTestData} email
 * instead.
 *
 * The collection's own script generates the email into the Postman
 * environment variable {@code userEmail} (the GraphQL mutation's {@code
 * email} variable is {@code {{userEmail}}}) - the run itself is the source
 * of the value, not an input to it. The installed Postman CLI has no
 * environment-export flag (confirmed against v1.33.1 and the official CLI
 * docs - there is no equivalent of Newman's {@code --export-environment}),
 * so instead the collection's test script logs the generated email as a
 * {@code INVITATION_EMAIL=...} marker line, and that's grepped out of the
 * run's captured stdout.
 *
 * Requires the Postman CLI ("postman" on PATH, already logged in via
 * "postman login --with-api-key ...") to be set up on whatever machine runs
 * the test - see scripts/install-postman-cli.sh (mac/linux),
 * scripts/install-postman-cli.ps1 (Windows), and
 * .github/workflows/appium-tests.yml for the CI setup. Deliberately not
 * auto-installed from here: that would mean silently reaching out to the
 * network and modifying the machine's global CLI state from inside a test
 * run.
 */
public final class InvitationApiClient {

	private static final Logger logger = LoggerFactory.getLogger(InvitationApiClient.class);

	// Postman workspace IDs come from configuration (.env / environment), never from source.

	/**
	 * Prefix the collection's test script logs the generated email under
	 * (e.g. {@code INVITATION_EMAIL=qa.user+12345@example.com}), so it
	 * can be recovered from the CLI's captured stdout.
	 */
	private static final String EMAIL_MARKER_PREFIX = "INVITATION_EMAIL=";
	private static final Pattern EMAIL_MARKER_PATTERN = Pattern.compile(
			Pattern.quote(EMAIL_MARKER_PREFIX) + "(\\S+)");

	/** Non-instantiable - every member is static. */
	private InvitationApiClient() {
	}

	/**
	 * Runs the invitation collection and returns the email it generated.
	 */
	public static String inviteAndCaptureEmail() {
		verifyPostmanCliAvailable();
		return readUserEmail(runCollection());
	}

	/**
	 * Fails fast with an actionable message rather than letting a missing
	 * "postman" executable surface as an opaque ProcessBuilder IOException -
	 * the most likely failure on a QA machine that hasn't run the install
	 * script yet, especially since most of the team runs Windows locally
	 * while this was written/tested on macOS.
	 */
	private static void verifyPostmanCliAvailable() {
		try {
			Process process = new ProcessBuilder("postman", "--version")
					.redirectErrorStream(true)
					.start();
			process.getInputStream().readAllBytes();
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IllegalStateException(
						"'postman --version' exited with code " + exitCode + " - is the Postman CLI installed correctly?");
			}
		} catch (IOException e) {
			throw new IllegalStateException(
					"Postman CLI ('postman' on PATH) not found. Run scripts/install-postman-cli.sh (mac/linux) "
							+ "or scripts/install-postman-cli.ps1 (Windows) before running this test.", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while checking for the Postman CLI", e);
		}
	}

	/**
	 * Runs the invitation collection and returns its combined stdout/stderr,
	 * which includes the {@link #EMAIL_MARKER_PREFIX} line logged by the
	 * collection's test script.
	 *
	 * A non-zero exit code is a real, deterministic collection failure and
	 * fails fast as {@link IllegalStateException} - retrying won't fix a
	 * broken mutation. An {@link IOException} starting/reading the CLI
	 * process, on the other hand, is the kind of transient connection
	 * hangup {@code @RetryingTest} (see the {@code Signup*}/{@code Journey*}
	 * test classes) is meant to retry the whole scenario for, so it's
	 * rethrown as {@link UncheckedIOException} rather than folded into the
	 * same {@link IllegalStateException} type as a real failure.
	 */
	private static String runCollection() {
		List<String> command = List.of("postman", "collection", "run", Config.require("POSTMAN_COLLECTION_ID"),
				"-e", Config.require("POSTMAN_ENVIRONMENT_ID"), "-i", Config.require("POSTMAN_INTEGRATION_ID"));

		try {
			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exitCode = process.waitFor();
			logger.info("POSTMAN RESPONSE START (invite collection, exit {})\n{}\nPOSTMAN RESPONSE END", exitCode, output);
			if (exitCode != 0) {
				throw new IllegalStateException("Postman invitation collection run failed (exit " + exitCode + "):\n" + output);
			}
			return output;
		} catch (IOException e) {
			throw new UncheckedIOException("Could not run the Postman CLI invitation collection", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running the Postman CLI invitation collection", e);
		}
	}

	/**
	 * Extracts the generated email from the {@link #EMAIL_MARKER_PREFIX} line
	 * in the collection run's output.
	 */
	private static String readUserEmail(String output) {
		Matcher matcher = EMAIL_MARKER_PATTERN.matcher(output);
		if (!matcher.find()) {
			throw new IllegalStateException(
					"Postman invitation collection run did not log a '" + EMAIL_MARKER_PREFIX + "' line:\n" + output);
		}
		// The Postman CLI renders a logged string wrapped in quotes (e.g.
		// "console.log 'INVITATION_EMAIL=...'"), not something the collection's
		// script controls - (\S+) above greedily sweeps up that trailing quote
		// since there's no whitespace before it, so it has to be stripped here.
		String email = matcher.group(1).replaceAll("['\"]+$", "");
		logger.info("Invitation collection generated email {}", email);
		return email;
	}
}
