package com.atlas.liquidity.refdata.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for setting an account's liquidity buffer.
 *
 * <p>The amount arrives as a String for the same precision reason the response
 * returns one, and is validated by shape before anything tries to parse it.
 *
 * <p><b>Why the regex as well as {@code @NotBlank}.</b> Without it, a body of
 * {@code {"amount": "'; DROP TABLE settlement_account; --"}} reaches
 * {@code new BigDecimal(...)} and produces a {@code NumberFormatException} - a
 * 400, so the outcome is fine, but the failure happens deep in the call stack
 * with a message that means nothing to the caller. Validating at the edge means
 * the request is rejected before it touches any logic, with an error that says
 * what was wrong. Defence in depth: the regex here, the {@code Money}
 * constructor behind it, and the CHECK constraint in the database behind that.
 *
 * <p>No currency field. The account already has one, and accepting a currency
 * would invite a caller to set a EUR buffer on a USD account. An API that
 * cannot express an invalid request is better than one that validates carefully.
 *
 * @param amount decimal amount, e.g. {@code "25000000.00"}
 */
public record UpdateLiquidityBufferRequest(
        @NotBlank(message = "amount is required")
        @Pattern(regexp = "^\\d{1,19}(\\.\\d{1,4})?$",
                 message = "amount must be a non-negative decimal with at most 4 decimal places")
        String amount) {
}
