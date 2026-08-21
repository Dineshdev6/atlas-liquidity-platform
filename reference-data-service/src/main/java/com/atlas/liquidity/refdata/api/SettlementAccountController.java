package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.common.query.Page;
import com.atlas.liquidity.common.query.PageRequest;
import com.atlas.liquidity.common.query.SortDirection;
import com.atlas.liquidity.refdata.application.LiquidityBufferAdjustmentService;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.domain.SettlementAccountSortField;
import com.atlas.liquidity.refdata.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API over settlement account reference data.
 *
 * <p><b>Why the URI carries the version.</b> Once a downstream team integrates you
 * cannot make a breaking change without a migration path. The alternatives are a
 * custom header ({@code X-API-Version: 2}) and media-type versioning
 * ({@code Accept: application/vnd.atlas.v2+json}); purists prefer both, because a
 * URI should identify a resource rather than a representation of it, and in theory
 * they are right. In an operational bank URI versioning wins for unglamorous
 * reasons: it is visible in access logs and traces, trivially routable at a gateway,
 * and unambiguous to someone debugging at 2am with curl. Header versioning is
 * invisible in a log line; media-type versioning breaks pasting a URL into a
 * browser. Name the trade-off rather than declaring a winner.
 */
@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Settlement accounts",
     description = "Nostro and settlement accounts, and their intraday liquidity buffers")
public class SettlementAccountController {

    private static final String OPERATION_ADJUST_BUFFER = "adjust-liquidity-buffer";

    private final SettlementAccountRepository repository;
    private final LiquidityBufferAdjustmentService adjustmentService;
    private final IdempotencyService idempotencyService;

    public SettlementAccountController(
            SettlementAccountRepository repository,
            LiquidityBufferAdjustmentService adjustmentService,
            IdempotencyService idempotencyService) {
        this.repository = repository;
        this.adjustmentService = adjustmentService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Lists settlement accounts, filtered, sorted and paged.
     *
     * <p>Everything the caller sends is parsed and validated here, at the edge,
     * before anything else runs. An unvalidated {@code sort} reaching Spring Data
     * produces a 500 whose message lists the entity's real property names to
     * whoever sent the request.
     */
    @GetMapping
    @Operation(summary = "List settlement accounts",
               description = """
                       Returns a page of accounts. All filters are optional and combine into a
                       single query. Page size is capped at 200; `sort` accepts only the
                       documented field names.
                       """)
    @ApiResponse(responseCode = "200", description = "A page of accounts")
    @ApiResponse(responseCode = "400", description = "Invalid page, size, sort, direction or filter value")
    public PageResponse<SettlementAccountResponse> listAccounts(
            @Parameter(description = "ISO-4217 code, case-insensitive", example = "USD")
            @RequestParam(required = false) String currency,

            @Parameter(description = "Regulatory jurisdiction, case-insensitive", example = "US")
            @RequestParam(required = false) String jurisdiction,

            @Parameter(description = "Owning legal entity, exact match", example = "ATLAS-BANK-NA")
            @RequestParam(required = false) String legalEntity,

            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rows per page, maximum 200", example = "20")
            @RequestParam(defaultValue = "20") int size,

            @Parameter(description = "accountId, accountNumber, legalEntity, currencyCode, "
                    + "jurisdiction or liquidityBuffer", example = "accountId")
            @RequestParam(defaultValue = "accountId") String sort,

            @Parameter(description = "asc or desc", example = "asc")
            @RequestParam(defaultValue = "asc") String direction) {

        SettlementAccountQuery query =
                new SettlementAccountQuery(currency, parseJurisdiction(jurisdiction), legalEntity);

        SettlementAccountSortField sortField = SettlementAccountSortField.parse(sort);

        PageRequest pageRequest = PageRequest.of(
                page, size, sortField.entityProperty(), SortDirection.parse(direction));

        Page<SettlementAccount> result = repository.search(query, pageRequest);

        // Page.map keeps the paging metadata untouched while converting the content,
        // so the counts cannot drift out of step with the rows.
        return PageResponse.from(result.map(SettlementAccountResponse::from));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Fetch one settlement account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "404", description = "No such account")
    public SettlementAccountResponse getAccount(
            @Parameter(description = "Internal account identifier", example = "ACC-US-0001")
            @PathVariable String accountId) {

        return repository.findByAccountId(accountId)
                .map(SettlementAccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Sets the buffer to an absolute value.
     *
     * <p><b>PUT, and therefore no idempotency key.</b> "Make it exactly this" is
     * naturally idempotent: send it ten times and the state is the same as sending
     * it once. A client whose call times out can simply retry. Compare the POST
     * below, which cannot.
     *
     * <p><b>The Layer 2 defect, now closed.</b> This used to read the account and
     * then update it in two separate transactions, so another request could change
     * or delete it in between. It now goes through the same application service as
     * the adjustment endpoint, so the read and the write share one transaction.
     */
    @PutMapping(path = "/{accountId}/liquidity-buffer", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set the liquidity buffer to an absolute value",
               description = "Naturally idempotent, so no Idempotency-Key is required.")
    @ApiResponse(responseCode = "200", description = "The updated account")
    @ApiResponse(responseCode = "400", description = "Malformed amount or body")
    @ApiResponse(responseCode = "404", description = "No such account")
    @ApiResponse(responseCode = "409", description = "Concurrent modification; re-read and retry")
    public SettlementAccountResponse setLiquidityBuffer(
            @PathVariable String accountId,
            @Valid @RequestBody UpdateLiquidityBufferRequest request) {

        // One call, one transaction. The service owns the boundary now, exactly as
        // it does for the adjustment below.
        return SettlementAccountResponse.from(
                adjustmentService.setTo(accountId, new BigDecimal(request.amount())));
    }

    /**
     * Applies a signed adjustment to the buffer, exactly once per idempotency key.
     *
     * <p><b>Why this one needs a key and the PUT does not.</b> "Add 5,000,000" is not
     * idempotent - applied twice, the money is wrong. And a client whose request
     * times out cannot tell whether the work happened, so it will retry. Without a
     * key, that retry is a second adjustment; with one, it is a replay of the first
     * response and the buffer is untouched.
     *
     * <p>This is the specific mechanism behind the job description's
     * "enterprise grade fast moving payment processing", and it is how Stripe, the
     * card networks and every payment rail handle retries.
     *
     * <p><b>The response carries {@code Idempotency-Replayed}</b> so a client
     * debugging a retry loop can see that its duplicate was recognised rather than
     * guessing from an indistinguishable 200.
     */
    @PostMapping(path = "/{accountId}/liquidity-buffer-adjustments",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Adjust the liquidity buffer by a signed delta",
               description = """
                       Requires an `Idempotency-Key` header. Repeating a request with the same key
                       returns the original response and performs no further work; the response
                       carries `Idempotency-Replayed: true`. Reusing a key with a different payload
                       is rejected with 422. Keys are honoured for 24 hours.
                       """)
    @ApiResponse(responseCode = "200", description = "The updated account")
    @ApiResponse(responseCode = "400", description = "Missing/blank key, malformed amount, or buffer would go negative")
    @ApiResponse(responseCode = "404", description = "No such account")
    @ApiResponse(responseCode = "409", description = "A request with this key is in flight; retry")
    @ApiResponse(responseCode = "422", description = "This key was already used for a different request")
    public ResponseEntity<SettlementAccountResponse> adjustLiquidityBuffer(
            @PathVariable String accountId,

            @Parameter(description = "Client-generated unique key, e.g. a UUID. Honoured for 24 hours.",
                       required = true, example = "6f1c9e2a-0b7d-4f1e-9a3c-2d5e8b1f4a70")
            @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,

            @Valid @RequestBody AdjustLiquidityBufferRequest request) {

        // An explicitly built, stable string - NOT the JSON of the request. Hashing
        // JSON looks obvious and is a trap: {"a":1,"b":2} and {"b":2,"a":1} are the
        // same request with different bytes, so reuse detection would fire on
        // requests that are actually identical. Canonical JSON is a hard problem;
        // this sidesteps it.
        String fingerprint = accountId + '|' + request.amount();

        IdempotencyService.IdempotentResult<SettlementAccountResponse> result =
                idempotencyService.execute(
                        idempotencyKey,
                        OPERATION_ADJUST_BUFFER,
                        fingerprint,
                        SettlementAccountResponse.class,
                        // Runs inside the idempotency service's transaction, so the
                        // read, the write and the key record all commit together.
                        () -> SettlementAccountResponse.from(
                                adjustmentService.adjustBy(accountId, new BigDecimal(request.amount()))));

        return ResponseEntity.ok()
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.value());
    }

    private Jurisdiction parseJurisdiction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Jurisdiction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown jurisdiction '" + value + "'. Valid values: "
                            + Arrays.toString(Jurisdiction.values()), e);
        }
    }
}
