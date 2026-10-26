package bflow.mcp.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/** Proves MCP UI labels use the monolith-compatible request locale. */
class MessageServiceTest {

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void resolvesEnglishAndFallsBackToSpanish() {
        LocaleConfig config = new LocaleConfig();
        MessageSource source = config.messageSource();
        MessageService messages = new MessageService(source);

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(messages.get("ui.budgets.title")).isEqualTo("Budgets");

        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(messages.get("ui.budgets.title")).isEqualTo("Presupuestos");
    }
}
