package bflow.mcp.i18n;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Resolves the request locale with the same contract as the BFlow API.
 */
@Configuration
public class LocaleConfig {

    /** Locales with a maintained translation. */
    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.ENGLISH, Locale.of("es"));

    /**
     * Exposes UTF-8 application messages with Spanish as the controlled
     * fallback instead of the JVM locale.
     * @return the application message source.
     */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    /**
     * Resolves the locale from {@code Accept-Language}; unsupported or
     * absent values use BFlow's Spanish default.
     * @return the request locale resolver.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.of("es"));
        resolver.setSupportedLocales(SUPPORTED_LOCALES);
        return resolver;
    }
}
