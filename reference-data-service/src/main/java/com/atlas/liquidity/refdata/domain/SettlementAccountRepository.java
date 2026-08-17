package com.atlas.liquidity.refdata.domain;

import com.atlas.liquidity.common.money.Money;
import java.util.List;
import java.util.Optional;

/**
 * Port through which the application reads and updates settlement accounts.
 *
 * <p>This interface has not changed shape since Layer 1 - it has only gained
 * one method. The implementation behind it changed completely: an in-memory map
 * became Hibernate over Postgres, with Flyway-managed schema, connection
 * pooling and optimistic locking.
 *
 * <p><b>That is the whole point, and it is worth pausing on.</b> Not one line
 * of {@code SettlementAccountController} changed when the database arrived. The
 * domain declared what it needed; the infrastructure was swapped underneath it.
 * When an interviewer asks why you would bother with a port when Spring Data
 * already gives you a repository interface, this is the answer - and the honest
 * follow-up is that on a simple CRUD service you would not bother, because the
 * indirection costs more than it saves.
 */
public interface SettlementAccountRepository {

    List<SettlementAccount> findAll();

    Optional<SettlementAccount> findByAccountId(String accountId);

    List<SettlementAccount> findByCurrency(String currencyCode);

    List<SettlementAccount> findByJurisdiction(Jurisdiction jurisdiction);

    /**
     * Sets a new liquidity buffer on an existing account.
     *
     * <p>Note the shape: it takes the fields that change, not a whole
     * {@code SettlementAccount}. A generic {@code save(account)} would let a
     * caller silently rewrite the BIC or the currency while "updating a buffer",
     * and would make the audit trail in Layer 8 useless because you could never
     * tell what the caller actually intended to change. Task-based operations
     * beat CRUD-shaped ones once anything is at stake.
     *
     * @return the updated account
     * @throws java.util.NoSuchElementException if no such account exists
     */
    SettlementAccount updateLiquidityBuffer(String accountId, Money newBuffer);
}
