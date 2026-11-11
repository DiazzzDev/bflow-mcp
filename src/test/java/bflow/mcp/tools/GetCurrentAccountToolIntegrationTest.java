package bflow.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import bflow.mcp.support.AbstractToolIntegrationTest;

/** Verifies the connected account answer comes from the authenticated profile endpoint. */
class GetCurrentAccountToolIntegrationTest extends AbstractToolIntegrationTest {

    @Autowired
    private GetCurrentAccountTool getCurrentAccountTool;

    @Test
    void returnsTheConnectedNameAndEmailWithoutProfileIdentifiers() {
        authenticateAs("user-123", "openid");
        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/users/me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{\"id\":\"private-id\","
                        + "\"name\":\"Mira\",\"email\":\"mira@example.com\"}}",
                        MediaType.APPLICATION_JSON));

        String result = getCurrentAccountTool.getCurrentAccount();

        assertThat(result).contains("Mira", "mira@example.com")
                .doesNotContain("private-id", "scope", "API");
        mockBflowApi.verify();
    }
}
