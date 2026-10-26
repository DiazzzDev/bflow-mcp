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
 * Exercises {@link ListCategoriesTool} through the real AOP chain
 * (observability → error handling → scope gate → the tool itself),
 * with only the BFlow API stubbed out.
 */
class ListCategoriesToolIntegrationTest extends AbstractToolIntegrationTest {

    @Autowired
    private ListCategoriesTool listCategoriesTool;

    @Test
    void returnsCategoriesAndForwardsTheExpectedHeaders() {
        authenticateAs("user-123", "bflow-mcp/categories.read");

        String categoriesJson = "{\"data\":[{\"id\":\"c-1\",\"name\":\"Food\","
                + "\"type\":\"EXPENSE\",\"icon\":\"fork\",\"color\":\"#FF0000\"}],"
                + "\"success\":true}";

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/categories"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(header("X-BFlow-Channel", "mcp"))
                .andExpect(header("X-BFlow-Actor-Type", "AGENT"))
                .andRespond(withSuccess(categoriesJson, MediaType.APPLICATION_JSON));

        String result = listCategoriesTool.listCategories();

        assertThat(result).isEqualTo(categoriesJson);
        mockBflowApi.verify();
    }

    @Test
    void deniesTheCallWhenTheScopeIsMissing() {
        authenticateWithNoScopes("user-123");

        // No expectation registered on mockBflowApi at all: if the tool
        // proceeded past the scope gate, this test would fail on
        // verify() below with "no further requests expected".
        String result = listCategoriesTool.listCategories();

        assertThat(result)
                .contains("\"code\":\"SCOPE_DENIED\"")
                .contains("\"retryable\":false");
        mockBflowApi.verify();
    }

    @Test
    void translatesAResourceAccessDeniedResponseFromBflow() {
        authenticateAs("user-123", "bflow-mcp/categories.read");

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/categories"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":\"Not allowed\"}"));

        String result = listCategoriesTool.listCategories();

        assertThat(result).contains("\"code\":\"RESOURCE_ACCESS_DENIED\"");
        mockBflowApi.verify();
    }
}