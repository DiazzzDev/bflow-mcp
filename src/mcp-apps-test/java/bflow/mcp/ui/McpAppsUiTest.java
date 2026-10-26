package bflow.mcp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Browser QA for the standard MCP Apps iframe and postMessage protocol. */
class McpAppsUiTest {

    private static HttpServer server;

    private static String baseUrl;

    @BeforeAll
    static void startHarnessServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", McpAppsUiTest::serveResource);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopHarnessServer() {
        server.stop(0);
    }

    @Test
    void budgetsShowsLoadingEmptyRetryableErrorAndLocalizedData() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            List<String> externalRequests = watchExternalRequests(page);
            page.setContent(harness("budgets.html", "dark"));
            Frame app = awaitApp(page, "budgets.html");

            sendResult(page, Map.of("status", "LOADING", "locale", "en",
                    "labels", budgetLabels("Budgets", "Loading budgets…",
                            "There are no budgets to show.", "Retry"),
                    "data", Map.of()));
            assertThat(app.locator("body").innerText()).contains("Loading budgets…");

            sendResult(page, Map.of("locale", "en", "labels", budgetLabels(
                    "Budgets", "Loading budgets…", "There are no budgets to show.",
                    "Retry"), "data", Map.of("content", List.of())));
            assertThat(app.locator("body").innerText()).contains("There are no budgets to show.");

            sendResult(page, Map.of("code", "UPSTREAM_UNAVAILABLE",
                    "message", "Try again shortly.", "retryable", true));
            app.getByRole(AriaRole.BUTTON,
                    new Frame.GetByRoleOptions().setName("Retry"))
                    .click();
            assertThat(page.evaluate("() => window.calls").toString())
                    .contains("listBudgets");

            sendResult(page, Map.of("locale", "es", "labels", budgetLabels(
                    "Presupuestos", "Cargando presupuestos…",
                    "No hay presupuestos para mostrar.", "Reintentar"),
                    "data", Map.of("content", List.of(Map.of("period", "MONTHLY",
                            "currency", "USD", "spent", 25.5, "remaining", 74.5,
                            "budgetLimit", 100, "percentage", 25.5)))));
            assertThat(app.locator("body").innerText()).contains("Presupuestos")
                    .contains("25,50");
            assertThat(app.locator("progress").count()).isEqualTo(1);
            assertThat(app.locator("html").getAttribute("data-theme")).isEqualTo("dark");
            assertThat(externalRequests).isEmpty();
        }
    }

    @Test
    void confirmationUsesKeyboardAndCallsTheOriginalTool() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch()) {
            Page page = browser.newPage();
            List<String> externalRequests = watchExternalRequests(page);
            page.setContent(harness("confirmation.html", "light"));
            Frame app = awaitApp(page, "confirmation.html");
            sendInput(page, "createTransaction", Map.of("title", "Coffee"));
            sendResult(page, Map.of("locale", "en", "message", "Review details.",
                    "labels", Map.of("title", "Confirm action", "confirm", "Confirm",
                            "cancel", "Cancel"), "preview", Map.of("title", "Coffee")));

            app.getByRole(AriaRole.BUTTON,
                    new Frame.GetByRoleOptions().setName("Confirm"))
                    .focus();
            app.getByRole(AriaRole.BUTTON,
                    new Frame.GetByRoleOptions().setName("Confirm"))
                    .press("Enter");
            assertThat(page.evaluate("() => window.calls").toString())
                    .contains("createTransaction").contains("confirmed");
            assertThat(externalRequests).isEmpty();
        }
    }

    @Test
    void transactionFormLoadsOptionsAndSubmitsWithoutExternalRequests() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch()) {
            Page page = browser.newPage();
            List<String> externalRequests = watchExternalRequests(page);
            page.setContent(harness("transaction-form.html", "dark"));
            Frame app = awaitApp(page, "transaction-form.html");
            sendResult(page, transactionLabels());
            assertThat(page.evaluate("() => window.calls").toString())
                    .contains("listWallets").doesNotContain("listCategories");
            sendResult(page, Map.of("data", Map.of("content", List.of(Map.of("id", "w1",
                    "name", "Wallet")))));
            assertThat(page.evaluate("() => window.calls").toString())
                    .contains("listCategories");
            sendResult(page, Map.of("data", Map.of("content", List.of(Map.of("id", "c1",
                    "name", "Food")))));
            app.locator("#name").fill("Lunch");
            app.locator("#amount").fill("12.50");
            app.locator("#date").fill("2026-10-25");
            app.locator("#submit").press("Enter");

            assertThat(page.evaluate("() => window.calls").toString())
                    .contains("listWallets").contains("listCategories")
                    .contains("createTransaction");
            assertThat(externalRequests).isEmpty();
        }
    }

    @Test
    void transactionFormShowsWalletScopeErrorAndKeepsCategoryResponseSeparate() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch()) {
            Page page = browser.newPage();
            page.setContent(harness("transaction-form.html", "light"));
            Frame app = awaitApp(page, "transaction-form.html");
            sendResult(page, transactionLabels());
            sendResult(page, Map.of("content", List.of(Map.of("type", "text",
                    "text", "{\"code\":\"SCOPE_DENIED\",\"message\":"
                            + "\"Missing bflow-mcp/wallets.read\","
                            + "\"retryable\":false}"))));
            sendResult(page, Map.of("data", Map.of("content", List.of(Map.of("id", "c1",
                    "name", "Food")))));

            assertThat(app.locator("#wallet-error").innerText())
                    .contains("bflow-mcp/wallets.read");
            assertThat(app.locator("#wallet").inputValue()).isEmpty();
            assertThat(app.locator("#category").inputValue()).isEqualTo("c1");
            assertThat(app.locator("#submit").isDisabled()).isTrue();
        }
    }

    @Test
    void transactionFormShowsCategoryScopeErrorNextToCategorySelect() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch()) {
            Page page = browser.newPage();
            page.setContent(harness("transaction-form.html", "light"));
            Frame app = awaitApp(page, "transaction-form.html");
            sendResult(page, transactionLabels());
            sendResult(page, Map.of("data", Map.of("content", List.of(Map.of("id", "w1",
                    "name", "Wallet")))));
            sendResult(page, Map.of("content", List.of(Map.of("type", "text",
                    "text", "{\"code\":\"SCOPE_DENIED\",\"message\":"
                            + "\"Missing bflow-mcp/categories.read\","
                            + "\"retryable\":false}"))));

            assertThat(app.locator("#wallet").inputValue()).isEqualTo("w1");
            assertThat(app.locator("#category-error").innerText())
                    .contains("bflow-mcp/categories.read");
            assertThat(app.locator("#category").inputValue()).isEmpty();
            assertThat(app.locator("#submit").isDisabled()).isTrue();
        }
    }

    private static Frame awaitApp(final Page page, final String resource) {
        page.waitForFunction("() => document.querySelector('iframe') !== null");
        page.waitForTimeout(100);
        return page.frames().stream()
                .filter(frame -> frame.url().endsWith(resource))
                .findFirst()
                .orElseThrow();
    }

    private static void sendInput(final Page page, final String name,
            final Map<String, Object> arguments) {
        post(page, Map.of("jsonrpc", "2.0", "method", "ui/notifications/tool-input",
                "params", Map.of("name", name, "arguments", arguments)));
    }

    private static void sendResult(final Page page, final Map<String, Object> result) {
        post(page, Map.of("jsonrpc", "2.0", "method", "ui/notifications/tool-result",
                "params", result));
    }

    private static void post(final Page page, final Map<String, Object> message) {
        page.evaluate("message => document.querySelector('iframe').contentWindow"
                + ".postMessage(message, '*')", message);
    }

    private static List<String> watchExternalRequests(final Page page) {
        List<String> requests = new CopyOnWriteArrayList<>();
        page.onRequest(request -> {
            if (!request.url().startsWith(baseUrl)
                    && !request.url().equals("about:blank")) {
                requests.add(request.url());
            }
        });
        return requests;
    }

    private static Map<String, String> budgetLabels(final String title,
            final String loading, final String empty, final String retry) {
        return Map.of("title", title, "loading", loading, "empty", empty,
                "retry", retry, "progress", "Progress", "spent", "Spent",
                "remaining", "Remaining", "limit", "Limit");
    }

    private static Map<String, Object> transactionLabels() {
        return Map.of("locale", "en", "labels", Map.ofEntries(
                Map.entry("title", "New transaction"),
                Map.entry("loading", "Loading options…"), Map.entry("type", "Type"),
                Map.entry("expense", "Expense"), Map.entry("income", "Income"),
                Map.entry("name", "Title"), Map.entry("amount", "Amount"),
                Map.entry("date", "Date"), Map.entry("wallet", "Wallet"),
                Map.entry("category", "Category"),
                Map.entry("description", "Description"),
                Map.entry("submit", "Continue"), Map.entry("empty", "No options"),
                Map.entry("retry", "Retry")), "data", Map.of());
    }

    private static String harness(final String resource, final String theme) {
        return "<iframe src='" + baseUrl + "/" + resource + "'></iframe>"
                + "<script>window.calls=[];window.externalRequests=[];"
                + "window.addEventListener('message',e=>{const d=e.data||{};"
                + "if(d.method==='ui/initialize'){e.source.postMessage({id:d.id,"
                + "result:{hostContext:{theme:'" + theme + "'}}},'*')}"
                + "if(d.method==='tools/call'){window.calls.push(d.params)}});</script>";
    }

    private static void serveResource(final HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring(1);
        try (var stream = McpAppsUiTest.class.getClassLoader()
                .getResourceAsStream("mcp-ui/" + name)) {
            if (stream == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = stream.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
