package bflow.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import bflow.mcp.support.AbstractToolIntegrationTest;

/**
 * Exercises {@link ListWalletsTool} through the real AOP chain
 * (observability → error handling → scope gate → the tool itself),
 * with only the BFlow API stubbed out.
 */
class ListWalletsToolIntegrationTest extends AbstractToolIntegrationTest {

    @Autowired
    private ListWalletsTool listWalletsTool;

    @Test
    void returnsWalletsAndForwardsTheExpectedHeaders() {
        authenticateAs("user-123", "bflow-mcp/wallets.read");

        String walletsJson = "{\"data\":{\"content\":[{\"id\":\"w-1\","
                + "\"name\":\"Personal\"}]},\"success\":true}";

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/wallets"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(header("X-BFlow-Channel", "mcp"))
                .andExpect(header("X-BFlow-Actor-Type", "AGENT"))
                .andRespond(withSuccess(walletsJson, MediaType.APPLICATION_JSON));

        String result = listWalletsTool.listWallets();

        assertThat(result).isEqualTo(walletsJson);
        mockBflowApi.verify();
    }

    @Test
    void deniesTheCallWhenTheScopeIsMissing() {
        authenticateWithNoScopes("user-123");

        // No expectation registered on mockBflowApi at all: if the tool
        // proceeded past the scope gate, this test would fail on
        // verify() below with "no further requests expected".
        String result = listWalletsTool.listWallets();

        assertThat(result)
                .contains("\"code\":\"SCOPE_DENIED\"")
                .contains("\"retryable\":false");
        mockBflowApi.verify();
    }

    @Test
    void translatesAResourceAccessDeniedResponseFromBflow() {
        authenticateAs("user-123", "bflow-mcp/wallets.read");

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/wallets"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"Not your wallet\"}"));

        String result = listWalletsTool.listWallets();

        assertThat(result).contains("\"code\":\"RESOURCE_ACCESS_DENIED\"");
        mockBflowApi.verify();
    }
}