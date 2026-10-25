package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit coverage for the CIMD advertisement in {@link AuthorizationServerMetadataController}. */
class AuthorizationServerMetadataControllerTest {

    private static final String BASE = "https://mcp.example.com";
    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_x";

    @Test
    void doesNotAdvertiseCimdWhenNoClientIsAllowlisted() {
        Map<String, Object> metadata = controller("").metadata();

        assertThat(metadata).doesNotContainKey("client_id_metadata_document_supported");
        assertThat(metadata).containsEntry("registration_endpoint", BASE + "/oauth/register");
    }

    @Test
    void advertisesCimdAlongsideDcrWhenAClientIsAllowlisted() {
        Map<String, Object> metadata =
                controller("https://claude.ai/oauth/mcp-oauth-client-metadata").metadata();

        assertThat(metadata).containsEntry("client_id_metadata_document_supported", true);
        assertThat(metadata).containsEntry("registration_endpoint", BASE + "/oauth/register");
    }

    @Test
    void keepsTheExistingFieldsUnchanged() {
        Map<String, Object> metadata = controller("").metadata();

        assertThat(metadata).containsEntry("issuer", BASE);
        assertThat(metadata).containsEntry("authorization_endpoint", BASE + "/oauth/authorize");
        assertThat(metadata).containsEntry("token_endpoint", BASE + "/oauth/token");
        assertThat(metadata).containsEntry("jwks_uri", ISSUER + "/.well-known/jwks.json");
        assertThat(metadata).containsKeys(
                "response_types_supported", "grant_types_supported",
                "code_challenge_methods_supported", "token_endpoint_auth_methods_supported",
                "scopes_supported");
    }

    private static AuthorizationServerMetadataController controller(final String cimdClientIds) {
        return new AuthorizationServerMetadataController(BASE, ISSUER, new CimdClientAllowlist(cimdClientIds));
    }
}
