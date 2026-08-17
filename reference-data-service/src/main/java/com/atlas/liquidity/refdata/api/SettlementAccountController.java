package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.List;
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
 * <p><b>Look at what did not change in Layer 2.</b> The database arrived,
 * Hibernate arrived, Flyway arrived, connection pooling arrived - and the read
 * methods below are byte-for-byte what they were in Layer 1. That is the port
 * and adapter arrangement paying for itself, and it is the concrete example to
 * reach for when someone asks why you would not just call Spring Data from the
 * controller.
 */
@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class SettlementAccountController {

    private final SettlementAccountRepository repository;

    public SettlementAccountController(SettlementAccountRepository repository) {
        this.repository = repository;
    }

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

    @GetMapping("/{accountId}")
    public SettlementAccountResponse getAccount(@PathVariable String accountId) {
        return repository.findByAccountId(accountId)
                .map(SettlementAccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Sets a new liquidity buffer on an account.
     *
     * <p><b>Why PUT and not PATCH or POST.</b> PUT is idempotent: sending the
     * same request ten times leaves the resource in the same state as sending it
     * once. That matters enormously in a payments environment, where a client
     * that times out will retry and you have no way to know whether the first
     * attempt landed. POST is not idempotent, so retrying it risks doing the
     * work twice - which is why Layer 3 adds idempotency keys for the operations
     * that genuinely must be POSTs.
     *
     * <p><b>{@code @Valid} is what activates Bean Validation.</b> Without it the
     * annotations on {@code UpdateLiquidityBufferRequest} are inert decoration.
     * A very common and very quiet bug: the constraints are right there in the
     * code, and nothing runs them.
     *
     * <p><b>An honest defect, which Layer 5 fixes.</b> This method reads the
     * account and then updates it, in two separate transactions. Between them,
     * another request could change the account - and in the worst case, delete
     * it. This is a read-modify-write race, and the correct fix is a single
     * transaction spanning both operations, which means an application service
     * owning the boundary. We do not add one yet because there is nothing else
     * for it to orchestrate. Being able to point at this and say "I know, here
     * is the race, here is the fix, here is why I deferred it" is worth more in
     * a code review than code with no known flaws.
     */
    @PutMapping(path = "/{accountId}/liquidity-buffer",
                consumes = MediaType.APPLICATION_JSON_VALUE)
    public SettlementAccountResponse updateLiquidityBuffer(
            @PathVariable String accountId,
            @Valid @RequestBody UpdateLiquidityBufferRequest request) {

        SettlementAccount existing = repository.findByAccountId(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // The currency comes from the account, never from the caller. The
        // client cannot express a mismatched buffer, so we never have to
        // validate for one here.
        Currency currency = Currency.getInstance(existing.currencyCode());
        Money newBuffer = Money.of(currency, new java.math.BigDecimal(request.amount()));

        return SettlementAccountResponse.from(repository.updateLiquidityBuffer(accountId, newBuffer));
    }

    private Jurisdiction parseJurisdiction(String value) {
        try {
            return Jurisdiction.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown jurisdiction '" + value + "'. Valid values: "
                            + java.util.Arrays.toString(Jurisdiction.values()), e);
        }
    }
}
