package site.kael.cpa.console.core.monitor.manager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D7 safe-name guard: sensitive shapes and candidate-field collisions are rejected. */
class SafeNameValidatorTest {

    @Test
    void acceptsNeutralNames() {
        assertTrue(SafeNameValidator.isSafe("nightly check", List.of("id-a", "name.json", "user@example.com")));
        assertTrue(SafeNameValidator.isSafe("task-01", List.of()));
    }

    @Test
    void rejectsEmailsUrlsPathsKeysAndControlChars() {
        List<String> noSensitive = List.of();
        for (String bad : new String[]{
                "admin@example.com",
                "https://evil.test",
                "http://x",
                "/etc/passwd",
                "../secret",
                "auth.json",
                "Authorization: Bearer xyz",
                "x-management-key",
                "api-key: sk-123",
                "my secret key",
                "token holder",
                "password1",
                "line1\nline2",
                "tab\there",
                "",
                "   ",
                "x".repeat(129)}) {
            assertFalse(SafeNameValidator.isSafe(bad, noSensitive), bad);
        }
    }

    @Test
    void rejectsNamesMatchingCandidateSensitiveFields() {
        List<String> sensitive = List.of("user@example.com", "id-a", "credential.json");
        assertFalse(SafeNameValidator.isSafe("user@example.com", sensitive));
        assertFalse(SafeNameValidator.isSafe("my id-a task", sensitive));
        assertFalse(SafeNameValidator.isSafe("credential.json backup", sensitive));
    }

    @Test
    void rejectsBlankAndNull() {
        assertFalse(SafeNameValidator.isSafe(null, List.of()));
        assertFalse(SafeNameValidator.isSafe("  ", List.of()));
    }
}
