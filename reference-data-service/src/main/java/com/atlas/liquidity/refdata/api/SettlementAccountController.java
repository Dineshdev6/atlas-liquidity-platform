package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.common.query.Page;
import com.atlas.liquidity.common.query.PageRequest;
import com.atlas.liquidity.common.query.SortDirection;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.domain.SettlementAccountSortField;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API over settlement account reference data.
 *
 * <p><b>Why the URI carries the version ({@code /api/v1/...}).</b> Once a
 * downstream team integrates, you cannot make a breaking change without a
 * migration path. The three options are URI versioning, a custom header
 * ({@code X-API-Version: 2}), and media-type versioning
 * ({@code Accept: application/vnd.atlas.v2+json}). Purists prefer the latter two
 * because the URI should identify a resource, not a representation of it - and
 * they are right in theory.
 *
 * <p>In an operational bank, URI versioning wins for unglamorous reasons: it is
 * visible in access logs and traces, trivially routable at a gateway (send
 * {@code /v1} to the old cluster, {@code /v2} to the new), and unambiguous to
 * someone debugging at 2am with a curl command. Header versioning is invisible in
 * a log line and easy to forget; media-type versioning breaks the "paste the URL
 * in a browser" workflow that every support engineer uses. Be ready to compare
 * all three - the interesting answer names the trade-off rather than declaring a
 * winner.
 */
@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class SettlementAccountController {

    private final SettlementAccountRepository repository;

    public SettlementAccountController(SettlementAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists settlement accounts, filtered, sorted and paged.
     *
     * <p><b>This method is where Layer 2's two-pass filtering defect died.</b> It
     * used to fetch by currency and then filter by jurisdiction in Java. Now the
     * criteria go to the database as one query and the result comes back already
     * paged.
     *
     * <p><b>Everything a caller sends is parsed and validated here, at the edge,
     * before anything else runs.</b> That ordering is the whole point of a
     * boundary: {@code page}, {@code size}, {@code sort} and {@code direction} are
     * checked against an allow-list or a numeric range, so the layers behind this
     * one can trust their inputs and stay simple. An unvalidated {@code sort}
     * reaching Spring Data produces a 500 whose message lists your entity's real
     * property names to whoever sent the request.
     *
     * <p>Defaults are deliberate: page 0, 20 rows, ascending by {@code accountId}.
     * A caller who supplies nothing gets a sane, bounded, deterministically
     * ordered response rather than the entire table.
     *
     * @param currency    optional ISO-4217 filter, case-insensitive
     * @param jurisdiction optional jurisdiction filter, case-insensitive
     * @param legalEntity optional exact-match filter on the owning entity
     * @param page        zero-based page index
     * @param size        rows per page, capped at {@link PageRequest#MAX_SIZE}
     * @param sort        one of the fields in {@link SettlementAccountSortField}
     * @param direction   {@code asc} or {@code desc}
     */
    @GetMapping
    public PageResponse<SettlementAccountResponse> listAccounts(
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String jurisdiction,
            @RequestParam(required = false) String legalEntity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "accountId") String sort,
            @RequestParam(defaultValue = "asc") String direction) {

        SettlementAccountQuery query = new SettlementAccountQuery(
                currency,
                parseJurisdiction(jurisdiction),
                legalEntity);

        // Allow-list resolution. An unknown field is a 400 naming the valid ones,
        // not a 500 disclosing our schema.
        SettlementAccountSortField sortField = SettlementAccountSortField.parse(sort);

        // PageRequest's own constructor enforces page >= 0 and 1 <= size <= 200,
        // throwing IllegalArgumentException, which the advice turns into a 400.
        // The validation lives with the type rather than being repeated by every
        // caller - which is the same reason Money owns its own scale.
        PageRequest pageRequest = PageRequest.of(
                page, size, sortField.entityProperty(), SortDirection.parse(direction));

        Page<SettlementAccount> result = repository.search(query, pageRequest);

        // Page.map keeps the paging metadata untouched while converting the
        // content, so the counts cannot drift out of step with the rows.
        return PageResponse.from(result.map(SettlementAccountResponse::from));
    }

    @GetMapping("/{accountId}")
    public SettlementAccountResponse getAccount(@PathVariable String accountId) {
        return repository.findByAccountId(accountId)
                .map(SettlementAccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Sets a new liquidity buffer on an account.
     *
     * <p><b>Why PUT and not PATCH or POST.</b> PUT is idempotent: sending the same
     * request ten times leaves the resource exactly as one request would. That
     * matters enormously in a payments environment, where a client whose call
     * times out has no way to know whether the work happened, and will retry.
     * POST is not idempotent, so retrying it risks doing the work twice - which is
     * why the next slice of Layer 3 adds idempotency keys for the operations that
     * genuinely must be POSTs.
     *
     * <p><b>{@code @Valid} is what activates Bean Validation.</b> Without it the
     * constraints on {@code UpdateLiquidityBufferRequest} are inert decoration - a
     * very common and very quiet bug.
     *
     * <p><b>A known defect, still here, fixed in Layer 5.</b> This reads the
     * account and then updates it in two separate transactions. Between them
     * another request could change or delete it. The correct fix is one
     * transaction spanning both, owned by an application service - deferred until
     * there is something else for that service to orchestrate. Being able to point
     * at a race and explain why you deferred the fix beats code with no known
     * flaws.
     */
    @PutMapping(path = "/{accountId}/liquidity-buffer",
                consumes = MediaType.APPLICATION_JSON_VALUE)
    public SettlementAccountResponse updateLiquidityBuffer(
            @PathVariable String accountId,
            @Valid @RequestBody UpdateLiquidityBufferRequest request) {

        SettlementAccount existing = repository.findByAccountId(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // The currency comes from the account, never from the caller, so the
        // client cannot express a mismatched buffer in the first place.
        Currency currency = Currency.getInstance(existing.currencyCode());
        Money newBuffer = Money.of(currency, new BigDecimal(request.amount()));

        return SettlementAccountResponse.from(repository.updateLiquidityBuffer(accountId, newBuffer));
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
