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
            page.waitForFunction("() => window.calls.some(call => "
                    + "call.name === 'listBudgets')");
            assertThat(page.evaluate("() => window.calls").toString())
                    .contains("listBudgets");

            sendResultJson(page, "{\"locale\":\"es\",\"labels\":"
                    + "{\"title\":\"Presupuestos\",\"progressUnavailable\":"
                    + "\"Sin datos de progreso\",\"periodMonthly\":\"Mensual\","
                    + "\"periodUnknown\":\"Otro periodo\",\"spent\":\"Gastado\","
                    + "\"remaining\":\"Restante\",\"limit\":\"Límite\"},"
                    + "\"data\":{\"content\":[{\"period\":\"MONTHLY\","
                    + "\"currency\":\"USD\",\"spent\":null,"
                    + "\"remaining\":null,\"budgetLimit\":100,"
                    + "\"percentage\":null}]}}");
            app.getByText("Sin datos de progreso").waitFor();
            assertThat(app.locator("body").innerText()).contains("Sin datos de progreso")
                    .contains("—").contains("Mensual").doesNotContain("MONTHLY");
            assertThat(app.locator("progress").count()).isZero();

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
            sendInput(page, Map.of("type", "EXPENSE", "title", "Coffee"));
            sendResult(page, Map.of("locale", "en", "message", "Review details.",
                    "toolName", "createTransaction",
                    "labels", Map.of("title", "Confirm action", "confirm", "Confirm",
                            "cancel", "Cancel"), "preview", Map.of("title", "Coffee")));

            app.getByRole(AriaRole.BUTTON,
                    new Frame.GetByRoleOptions().setName("Confirm"))
                    .focus();
            app.getByRole(AriaRole.BUTTON,
                    new Frame.GetByRoleOptions().setName("Confirm"))
                    .press("Enter");
            page.waitForFunction("() => window.calls.some(call => "
                    + "call.name === 'createTransaction' && call.arguments.confirmed === true)");
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
            assertThat(page.evaluate("() => JSON.stringify(window.calls)").toString())
                    .contains("listWallets").doesNotContain("listCategories");
            sendToolResponse(page, "listWallets", Map.of("data", Map.of("content",
                    List.of(Map.of("id", "w1", "name", "Other wallet",
                                    "defaultWallet", false),
                            Map.of("id", "w-default", "name", "Default wallet",
                                    "defaultWallet", true)))));
            assertThat(page.evaluate("() => JSON.stringify(window.calls)").toString())
                    .contains("listCategories");
            sendToolResponse(page, "listCategories", Map.of("data", Map.of("content",
                    List.of(Map.of("id", "c1", "name", "Food", "type", "EXPENSE"),
                            Map.of("id", "c2", "name", "Salary", "type", "INCOME")))));
            assertThat(app.locator("#status").innerText()).isEmpty();
            assertThat(app.locator("main").getAttribute("aria-busy")).isEqualTo("false");
            assertThat(app.locator("#wallet").inputValue()).isEqualTo("w-default");
            assertThat(app.locator("#category").inputValue()).isEqualTo("c1");
            assertThat(app.locator("#expense").getAttribute("aria-pressed"))
                    .isEqualTo("true");
            assertThat(app.locator("#type-label").count()).isZero();
            assertThat(app.locator("#name").getAttribute("placeholder"))
                    .isEqualTo("E.g. Lunch");
            assertThat(app.locator("#description-optional").innerText())
                    .isEqualTo("(optional)");
            assertThat(app.locator("#description").evaluate("e => getComputedStyle(e).resize"))
                    .isEqualTo("none");
            String expectedToday = (String) app.evaluate("() => {const d=new Date();"
                    + "d.setMinutes(d.getMinutes()-d.getTimezoneOffset());"
                    + "return d.toISOString().slice(0,10)}");
            assertThat(app.locator("#date").inputValue()).isEqualTo(expectedToday);
            app.locator("#yesterday").click();
            String expectedYesterday = (String) app.evaluate("() => {const d=new Date();"
                    + "d.setDate(d.getDate()-1);d.setMinutes(d.getMinutes()-d.getTimezoneOffset());"
                    + "return d.toISOString().slice(0,10)}");
            assertThat(app.locator("#date").inputValue()).isEqualTo(expectedYesterday);
            app.locator("#today").click();
            assertThat(app.locator("#date").inputValue()).isEqualTo(expectedToday);
            app.locator("#income").click();
            assertThat(app.locator("#income").getAttribute("aria-pressed"))
                    .isEqualTo("true");
            assertThat(app.locator("#category").inputValue()).isEqualTo("c2");
            app.locator("#name").fill("Lunch");
            app.locator("#amount").fill("12.50");
            app.locator("#date").fill("2026-10-25");
            app.locator("#submit").press("Enter");

            page.waitForFunction("() => window.calls.some(call => "
                    + "call.name === 'createTransaction')");

            assertThat(page.evaluate("() => JSON.stringify(window.calls)").toString())
                    .contains("listWallets").contains("listCategories")
                    .contains("createTransaction");
            assertThat(page.evaluate("() => JSON.stringify(window.calls)").toString())
                    .contains("INCOME").contains("w-default").contains("c2");
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
            sendToolResponse(page, "listWallets", Map.of("code", "SCOPE_DENIED",
                    "message", "Missing bflow-mcp/wallets.read", "retryable", false));
            sendToolResponse(page, "listCategories", Map.of("data", Map.of("content",
                    List.of(Map.of("id", "c1", "name", "Food")))));

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
            sendToolResponse(page, "listWallets", Map.of("data", Map.of("content",
                    List.of(Map.of("id", "w1", "name", "Wallet")))));
            sendToolResponse(page, "listCategories", Map.of("code", "SCOPE_DENIED",
                    "message", "Missing bflow-mcp/categories.read", "retryable", false));

            assertThat(app.locator("#wallet").inputValue()).isEqualTo("w1");
            assertThat(app.locator("#category-error").innerText())
                    .contains("bflow-mcp/categories.read");
            assertThat(app.locator("#category").inputValue()).isEmpty();
            assertThat(app.locator("#submit").isDisabled()).isTrue();
        }
    }

    @Test
    void previousHtmlFailsTheProtocolHarness() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.setContent(harness("budgets-legacy.html", "dark"));
            page.waitForFunction("() => window.initialized === true");
            page.waitForTimeout(300);
            assertThat(page.evaluate("() => window.protocolErrors").toString())
                    .contains("missing appInfo", "missing protocolVersion",
                            "notification ui/notifications/initialized must not have id",
                            "missing ui/notifications/size-changed");
        }
    }

    private static Frame awaitApp(final Page page, final String resource) {
        page.waitForFunction("() => document.querySelector('iframe') !== null");
        page.waitForFunction("() => window.initialized && window.sizeChanged > 0");
        assertProtocolHealthy(page);
        return page.frames().stream()
                .filter(frame -> frame.url().endsWith(resource))
                .findFirst()
                .orElseThrow();
    }

    private static void sendInput(final Page page, final Map<String, Object> arguments) {
        post(page, Map.of("jsonrpc", "2.0", "method", "ui/notifications/tool-input",
                "params", Map.of("arguments", arguments)));
    }

    private static void sendResult(final Page page, final Map<String, Object> result) {
        page.waitForFunction("() => window.initialized === true");
        post(page, Map.of("jsonrpc", "2.0", "method", "ui/notifications/tool-result",
                "params", result));
    }

    private static void sendToolResponse(final Page page, final String toolName,
            final Map<String, Object> result) {
        page.waitForFunction("name => window.toolRequests.some(request => "
                + "request.params.name === name && !request.replied)", toolName);
        page.evaluate("response => window.reply(response.name, response.result)",
                Map.of("name", toolName, "result", result));
    }

    private static void sendResultJson(final Page page, final String json) {
        page.waitForFunction("() => window.initialized === true");
        page.evaluate("json => document.querySelector('iframe').contentWindow"
                + ".postMessage({jsonrpc:'2.0',method:'ui/notifications/tool-result',"
                + "params:JSON.parse(json)}, '*')", json);
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
        boolean spanish = "Presupuestos".equals(title);
        return Map.ofEntries(Map.entry("title", title), Map.entry("loading", loading),
                Map.entry("empty", empty), Map.entry("retry", retry),
                Map.entry("progress", "Progress"),
                Map.entry("progressUnavailable", "Sin datos de progreso"),
                Map.entry("spent", "Spent"), Map.entry("remaining", "Remaining"),
                Map.entry("limit", "Limit"),
                Map.entry("periodDaily", spanish ? "Diario" : "Daily"),
                Map.entry("periodWeekly", spanish ? "Semanal" : "Weekly"),
                Map.entry("periodMonthly", spanish ? "Mensual" : "Monthly"),
                Map.entry("periodYearly", spanish ? "Anual" : "Yearly"),
                Map.entry("periodUnknown", spanish ? "Otro periodo" : "Other period"));
    }

    private static Map<String, Object> transactionLabels() {
        return Map.of("locale", "en", "labels", Map.ofEntries(
                Map.entry("title", "New transaction"),
                Map.entry("loading", "Loading options…"), Map.entry("type", "Type"),
                Map.entry("typeAccessible", "Transaction type"),
                Map.entry("expense", "Expense"), Map.entry("income", "Income"),
                Map.entry("name", "Title"), Map.entry("amount", "Amount"),
                Map.entry("date", "Date"), Map.entry("wallet", "Wallet"),
                Map.entry("category", "Category"),
                Map.entry("description", "Description"),
                Map.entry("optional", "(optional)"),
                Map.entry("namePlaceholder", "E.g. Lunch"),
                Map.entry("amountPlaceholder", "E.g. 12.50"),
                Map.entry("dateExample", "E.g. 10/26/2026"),
                Map.entry("descriptionPlaceholder", "Add a note if needed"),
                Map.entry("today", "Today"), Map.entry("yesterday", "Yesterday"),
                Map.entry("optionLoadError", "Could not load the options."),
                Map.entry("submit", "Continue"), Map.entry("empty", "No options"),
                Map.entry("retry", "Retry")), "data", Map.of());
    }

    private static String harness(final String resource, final String theme) {
        return "<iframe src='" + baseUrl + "/" + resource + "'></iframe>"
                + "<script>window.calls=[];window.toolRequests=[];window.protocolErrors=[];window.initialized=false;"
                + "window.reply=(name,result)=>{const request=window.toolRequests.find(item=>item.params.name===name&&!item.replied);if(!request)throw Error('No pending '+name+' request');request.replied=true;document.querySelector('iframe').contentWindow.postMessage({jsonrpc:'2.0',id:request.id,result:{content:[{type:'text',text:JSON.stringify(result)}]}},'*')};"
                + "window.sizeChanged=0;window.addEventListener('message',e=>{const d=e.data||{};"
                + "if(d.method==='ui/initialize'){if(!d.params?.appInfo)window.protocolErrors.push('missing appInfo');"
                + "if(!d.params?.protocolVersion)window.protocolErrors.push('missing protocolVersion');"
                + "if(!d.params?.appCapabilities)window.protocolErrors.push('missing appCapabilities');"
                + "e.source.postMessage({jsonrpc:'2.0',id:d.id,result:{protocolVersion:'2026-01-26',"
                + "hostCapabilities:{},hostInfo:{name:'basic-host',version:'1.0.0'},"
                + "hostContext:{theme:'" + theme + "'}}},'*')}"
                + "if(d.method?.startsWith('ui/notifications/')&&'id'in d)window.protocolErrors.push('notification '+d.method+' must not have id');"
                + "if(d.method==='ui/notifications/initialized'){window.initialized=true;"
                + "setTimeout(()=>{if(window.sizeChanged===0)window.protocolErrors.push('missing ui/notifications/size-changed')},200)}"
                + "if(d.method==='ui/notifications/size-changed')window.sizeChanged++;"
                + "if((d.method==='ui/notifications/tool-input'||d.method==='ui/notifications/tool-result')&&!window.initialized)window.protocolErrors.push('host sent app data before initialized');"
                + "if(d.method==='tools/call'){if(!window.initialized)window.protocolErrors.push('app called a tool before initialized');if(!('id'in d))window.protocolErrors.push('tools/call must be a request');window.calls.push(d.params);window.toolRequests.push({id:d.id,params:d.params,replied:false})}});</script>";
    }

    private static void assertProtocolHealthy(final Page page) {
        assertThat(page.evaluate("() => window.protocolErrors").toString())
                .as("MCP Apps protocol violations")
                .isEqualTo("[]");
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
