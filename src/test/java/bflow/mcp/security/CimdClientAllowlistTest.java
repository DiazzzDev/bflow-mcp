package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit coverage for {@link CimdClientAllowlist}. */
class CimdClientAllowlistTest {

    private static final String CLAUDE = "https://claude.ai/oauth/mcp-oauth-client-metadata";
    private static final String CHATGPT = "https://chatgpt.com/oauth/client.json";

    private final CimdClientAllowlist allowlist = new CimdClientAllowlist(CLAUDE + ", " + CHATGPT);

    @Test
    void anEmptyListDisablesCimd() {
        CimdClientAllowlist empty = new CimdClientAllowlist("");

        assertThat(empty.isEnabled()).isFalse();
        assertThat(empty.isAllowed(CLAUDE)).isFalse();
    }

    @Test
    void aBlankListDisablesCimd() {
        assertThat(new CimdClientAllowlist(" , ,").isEnabled()).isFalse();
    }

    @Test
    void aNonEmptyListEnablesCimd() {
        assertThat(allowlist.isEnabled()).isTrue();
    }

    @Test
    void allowsAnExactMatchAndTrimsWhitespace() {
        assertThat(allowlist.isAllowed(CLAUDE)).isTrue();
        assertThat(allowlist.isAllowed(CHATGPT)).isTrue();
    }

    @Test
    void rejectsAnythingNotOnTheList() {
        assertThat(allowlist.isAllowed("https://evil.example.com/client.json")).isFalse();
        assertThat(allowlist.isAllowed(CLAUDE + "/")).isFalse();
        assertThat(allowlist.isAllowed("https://claude.ai/oauth/other")).isFalse();
        assertThat(allowlist.isAllowed(null)).isFalse();
    }

    @Test
    void rejectsAnHttpEntryAtStartup() {
        assertThatThrownBy(() -> new CimdClientAllowlist("http://claude.ai/oauth/client.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnEntryWithoutAPathAtStartup() {
        assertThatThrownBy(() -> new CimdClientAllowlist("https://claude.ai"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CimdClientAllowlist("https://claude.ai/"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsFragmentsCredentialsAndDotSegmentsAtStartup() {
        assertThatThrownBy(() -> new CimdClientAllowlist("https://claude.ai/client.json#frag"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CimdClientAllowlist("https://user:pw@claude.ai/client.json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CimdClientAllowlist("https://claude.ai/a/../client.json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CimdClientAllowlist("https://claude.ai/./client.json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recognisesTheShapeOfACimdClientId() {
        assertThat(CimdClientAllowlist.isCimdClientId(CLAUDE)).isTrue();
        assertThat(CimdClientAllowlist.isCimdClientId("eyJhbGciOiJIUzI1NiJ9.payload.sig")).isFalse();
        assertThat(CimdClientAllowlist.isCimdClientId("http://claude.ai/client.json")).isFalse();
        assertThat(CimdClientAllowlist.isCimdClientId(null)).isFalse();
    }
}
