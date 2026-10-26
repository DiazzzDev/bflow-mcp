package bflow.mcp.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

/** Resolves a message against the locale of the current MCP request. */
@Service
public class MessageService {

    /** Application message source. */
    private final MessageSource messageSource;

    /**
     * Creates the service.
     * @param messageSource source configured by {@link LocaleConfig}.
     */
    public MessageService(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Resolves a message for the active request locale.
     * @param code message bundle key.
     * @param args optional placeholder values.
     * @return the localized message.
     */
    public String get(final String code, final Object... args) {
        return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
    }
}
