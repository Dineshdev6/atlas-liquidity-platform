package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API over settlement account reference data.
 *
 * <p><b>Why the URI is versioned ({@code /api/v1/...}).</b> Once a downstream
 * team integrates, you cannot make a breaking change without a migration path.
 * URI versioning is the least elegant and most operationally practical of the
 * options - it is visible in logs, trivially routable at the gateway, and
 * unambiguous to a caller debugging at 2am. Be ready to compare it with header
 * and media-type versioning; the interesting answer names the trade-off rather
 * than declaring a winner.
 *
 * <p><b>Why constructor injection.</b> The dependency is {@code final}, so the
 * object cannot exist in a half-built state, and the class is trivially
 * instantiable in a unit test with a stub - no Spring context required. Field
 * injection with {@code @Autowired} gives up both of those properties. Since
 * Spring 4.3 the {@code @Autowired} annotation on a single constructor is
 * redundant, which is why you do not see it here.
 *
 * <p>Layer 3 extends this with pagination, OpenAPI documentation, and write
 * operations guarded by idempotency keys.
 */
@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class SettlementAccountController {

    private final SettlementAccountRepository repository;

    public SettlementAccountController(SettlementAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists settlement accounts, optionally filtered.
     *
     * <p>Filters are optional query parameters rather than separate endpoints
     * ({@code /accounts/by-currency/USD}) because they are attributes of the
     * same collection resource, not different resources. Keeping one endpoint
     * also means one place to add pagination and authorisation later.
     */
    @GetMapping
    public List<SettlementAccountResponse> listAccounts(
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String jurisdiction) {

        var accounts = repository.findAll();

        if (currency != null && !currency.isBlank()) {
            accounts = repository.findByCurrency(currency);
        }

        if (jurisdiction != null && !jurisdiction.isBlank()) {
            Jurisdiction parsed = parseJurisdiction(jurisdiction);
            accounts = accounts.stream()
                    .filter(account -> account.jurisdiction() == parsed)
                    .toList();
        }

        return accounts.stream().map(SettlementAccountResponse::from).toList();
    }

    /**
     * Fetches a single account, or 404 via {@link GlobalExceptionHandler}.
     *
     * <p>{@code orElseThrow} rather than returning {@code ResponseEntity} with a
     * manual status: the controller states the business outcome and lets the
     * advice own the HTTP mapping. Consistent, and it keeps this method
     * readable.
     */
    @GetMapping("/{accountId}")
    public SettlementAccountResponse getAccount(@PathVariable String accountId) {
        return repository.findByAccountId(accountId)
                .map(SettlementAccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private Jurisdiction parseJurisdiction(String value) {
        try {
            return Jurisdiction.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Rethrown with a message a caller can act on, rather than
            // "No enum constant com.atlas...". Small thing; big difference to
            // whoever is integrating against you.
            throw new IllegalArgumentException(
                    "Unknown jurisdiction '" + value + "'. Valid values: "
                            + java.util.Arrays.toString(Jurisdiction.values()), e);
        }
    }
}
