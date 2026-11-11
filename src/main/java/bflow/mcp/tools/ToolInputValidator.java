package bflow.mcp.tools;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Shared validation and low-friction defaults for write tools. */
final class ToolInputValidator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/El_Salvador");

    private ToolInputValidator() { }

    static String requiredText(final String value, final String field, final int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is required and must be at most " + max + " characters");
        }
        return value.trim();
    }

    static String optionalText(final String value, final String field, final int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.trim().length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return value.trim();
    }

    static void positiveAmount(final double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Amount must be a positive number");
        }
    }

    static String uuid(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    static String optionalUuid(final String value, final String field) {
        return value == null || value.isBlank() ? null : uuid(value, field);
    }

    static LocalDate dateOrToday(final String value, final String field) {
        if (value == null || value.isBlank()) {
            return LocalDate.now(BUSINESS_ZONE);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must use YYYY-MM-DD format");
        }
    }

    static LocalDate transactionDate(final String value, final String field) {
        LocalDate date = dateOrToday(value, field);
        if (date.isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw new IllegalArgumentException(field + " cannot be in the future");
        }
        return date;
    }

    static String enumValue(final String value, final String field, final String... allowed) {
        if (value != null) {
            for (String option : allowed) {
                if (option.equalsIgnoreCase(value.trim())) {
                    return option;
                }
            }
        }
        throw new IllegalArgumentException(field + " is not supported");
    }
}
