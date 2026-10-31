package automation.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Central place for environment-specific settings (IDs, test-account details,
 * file paths). Nothing here is hardcoded in the sources, so no real values
 * ever need to be committed.
 *
 * A key is resolved in this order, first hit wins:
 * <ol>
 *   <li>JVM system property with the same name, e.g. {@code -DTEST_EMAIL_DOMAIN=...}</li>
 *   <li>process environment variable, e.g. a CI secret/variable</li>
 *   <li>the {@code .env} file in the working directory (see {@code .env.example})</li>
 * </ol>
 */
public final class Config {

	private static final Map<String, String> DOT_ENV = loadDotEnv(Path.of(System.getProperty("user.dir"), ".env"));

	/** Non-instantiable - every member is static. */
	private Config() {
	}

	/** The value for {@code key}, or {@code defaultValue} when it is unset or blank. */
	public static String get(String key, String defaultValue) {
		String value = lookup(key);
		return value == null ? defaultValue : value;
	}

	/** The value for {@code key}; fails with an actionable message when it is unset or blank. */
	public static String require(String key) {
		String value = lookup(key);
		if (value == null) {
			throw new IllegalStateException("Missing required setting '" + key
					+ "'. Set it in .env (copy .env.example), as an environment variable, or with -D" + key + "=...");
		}
		return value;
	}

	private static String lookup(String key) {
		String value = System.getProperty(key);
		if (isBlank(value)) {
			value = System.getenv(key);
		}
		if (isBlank(value)) {
			value = DOT_ENV.get(key);
		}
		return isBlank(value) ? null : value.trim();
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** Parses a minimal {@code KEY=VALUE} file: {@code #} comments, optional quotes, no interpolation. */
	static Map<String, String> parseDotEnv(Iterable<String> lines) {
		Map<String, String> values = new HashMap<>();
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("export ")) {
				line = line.substring("export ".length()).trim();
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = line.substring(0, eq).trim();
			String value = line.substring(eq + 1).trim();
			if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
					|| value.startsWith("'") && value.endsWith("'"))) {
				value = value.substring(1, value.length() - 1);
			}
			values.put(key, value);
		}
		return values;
	}

	private static Map<String, String> loadDotEnv(Path file) {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try {
			return parseDotEnv(Files.readAllLines(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + file, e);
		}
	}
}
