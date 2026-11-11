package bflow.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Guards shared low-friction defaults and input checks for MCP writes. */
class ToolInputValidatorTest {

    @Test
    void usesTodayWhenAnOptionalDateIsBlank() {
        assertThat(ToolInputValidator.dateOrToday(null, "date"))
                .isEqualTo(LocalDate.now(java.time.ZoneId.of("America/El_Salvador")));
        assertThat(ToolInputValidator.transactionDate(null, "date"))
                .isEqualTo(LocalDate.now(java.time.ZoneId.of("America/El_Salvador")));
        assertThatThrownBy(() -> ToolInputValidator.transactionDate(
                LocalDate.now(java.time.ZoneId.of("America/El_Salvador")).plusDays(1)
                        .toString(), "date"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsMissingOptionalForeignKeysAndValidatesProvidedOnes() {
        assertThat(ToolInputValidator.optionalUuid(null, "categoryId")).isNull();
        assertThat(ToolInputValidator.optionalUuid(" ", "categoryId")).isNull();
        assertThat(ToolInputValidator.optionalUuid(
                "00000000-0000-4000-8000-000000000002", "categoryId"))
                .isEqualTo("00000000-0000-4000-8000-000000000002");
        assertThatThrownBy(() -> ToolInputValidator.optionalUuid("category", "categoryId"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveAndNonFiniteAmounts() {
        assertThatThrownBy(() -> ToolInputValidator.positiveAmount(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolInputValidator.positiveAmount(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolInputValidator.positiveAmount(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
