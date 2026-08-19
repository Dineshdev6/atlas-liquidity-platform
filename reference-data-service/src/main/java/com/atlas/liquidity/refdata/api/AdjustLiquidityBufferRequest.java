package com.atlas.liquidity.refdata.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for adjusting an account's liquidity buffer by a signed delta.
 *
 * <p>The amount is a String for the same precision reason the responses use one:
 * JSON has a single numeric type and every JavaScript client parses it as an
 * IEEE-754 double, which silently loses precision above about 15 significant
 * digits.
 *
 * <p>The regex allows a leading minus - unlike the absolute-set request, an
 * adjustment may reduce the buffer - but rejects everything else before any parsing
 * happens. Validating shape at the edge means a body like
 * {@code {"amount":"'; DROP TABLE ..."}} is refused with a message that says what
 * was wrong, rather than surfacing as a {@code NumberFormatException} from deep in
 * the call stack.
 *
 * <p>No currency field. The account has one, and accepting another would invite a
 * caller to adjust a USD account in EUR. An API that cannot express an invalid
 * request beats one that validates carefully.
 *
 * @param amount signed decimal, e.g. {@code "5000000.00"} or {@code "-250000.50"}
 * @param reason free-text audit note; optional now, mandatory once Layer 8 adds an
 *               audit trail worth the name
 */
@Schema(description = "A signed adjustment to an account's intraday liquidity buffer")
public record AdjustLiquidityBufferRequest(

        @Schema(description = "Signed decimal amount in the account's own currency",
                example = "5000000.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "amount is required")
        @Pattern(regexp = "^-?\\d{1,19}(\\.\\d{1,4})?$",
                 message = "amount must be a signed decimal with at most 4 decimal places")
        String amount,

        @Schema(description = "Why the adjustment was made", example = "Intraday buffer top-up for CLS window")
        String reason) {
}
