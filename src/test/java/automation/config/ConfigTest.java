package automation.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure unit tests - no emulator or Appium needed. */
class ConfigTest {

	@Test
	void parsesKeyValuePairsCommentsAndQuotes() {
		Map<String, String> parsed = Config.parseDotEnv(List.of(
				"# a comment", "", "A=1", "B = \"two words\"", "export C='3'", "NOEQUALS", "=novalue"));
		assertEquals(Map.of("A", "1", "B", "two words", "C", "3"), parsed);
	}

	@Test
	void systemPropertyWinsAndDefaultsApply() {
		System.setProperty("CONFIG_TEST_KEY", "from-prop");
		try {
			assertEquals("from-prop", Config.get("CONFIG_TEST_KEY", "fallback"));
		} finally {
			System.clearProperty("CONFIG_TEST_KEY");
		}
		assertEquals("fallback", Config.get("CONFIG_TEST_KEY", "fallback"));
	}

	@Test
	void requireFailsWithActionableMessage() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> Config.require("CONFIG_TEST_MISSING_KEY"));
		assertEquals(true, e.getMessage().contains(".env.example"));
	}
}
