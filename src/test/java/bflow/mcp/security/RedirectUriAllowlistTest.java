package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit coverage for {@link RedirectUriAllowlist}. */
class RedirectUriAllowlistTest {

    private final RedirectUriAllowlist allowlist = new RedirectUriAllowlist(
            "https://claude.ai/api/mcp/auth_callback, https://chatgpt.com/connector_platform_oauth_redirect");

    @Test
    void allowsExactMatch() {
        assertThat(allowlist.isAllowed("https://claude.ai/api/mcp/auth_callback")).isTrue();
    }

    @Test
    void rejectsUriNotOnTheList() {
        assertThat(allowlist.isAllowed("https://evil.example.com/callback")).isFalse();
    }

    @Test
    void rejectsSameHostDifferentPath() {
        // Exact match only — a same-domain path Claude.ai never asks
        // for is not implicitly trusted (see ADR-0002).
        assertThat(allowlist.isAllowed("https://claude.ai/some/other/path")).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(allowlist.isAllowed(null)).isFalse();
    }

    @Test
    void trimsWhitespaceAroundEntries() {
        // The second entry in the constructor's CSV has a leading space.
        assertThat(allowlist.isAllowed("https://chatgpt.com/connector_platform_oauth_redirect")).isTrue();
    }

    @Test
    void constructorRejectsEmptyList() {
        assertThatThrownBy(() -> new RedirectUriAllowlist("  "))
                .isInstanceOf(IllegalStateException.class);
    }
}