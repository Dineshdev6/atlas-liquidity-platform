package com.atlas.liquidity.refdata.domain;

import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.common.query.Page;
import com.atlas.liquidity.common.query.PageRequest;
import java.util.Optional;

/**
 * Port through which the application reads and updates settlement accounts.
 *
 * <p><b>Layer 3 shrank this interface, which is the interesting part.</b> Layer 2
 * had four read methods - {@code findAll}, {@code findByAccountId},
 * {@code findByCurrency}, {@code findByJurisdiction}. Three of them collapse into
 * one {@code search}, and the interface got both smaller and more capable. That
 * is usually what happens when you replace "a method per question" with "a
 * question object".
 *
 * <p><b>{@code findAll} is gone on purpose, and it is not coming back.</b> An
 * unbounded read is a loaded gun pointed at your own service: it works
 * beautifully over six seed rows and takes production down the first time a
 * table gets large, because it asks the database for everything, holds it all in
 * heap, and serialises it. Removing the method means nobody can call it by
 * accident at 2am - which is a far stronger guarantee than a code comment asking
 * them not to. If a caller genuinely wants everything, they page through it and
 * the cost is visible.
 *
 * <p>Note what these signatures do <em>not</em> mention: Hibernate, Spring Data,
 * {@code Pageable}, {@code Specification}, SQL. The domain says what it needs -
 * filter criteria, a page request - and the adapter works out how.
 */
public interface SettlementAccountRepository {

    /**
     * Returns one page of accounts matching the given criteria.
     *
     * <p>A single query does all the filtering. Layer 2 fetched by currency and
     * then filtered by jurisdiction in Java, which returned the right answer for
     * the wrong reason: it only worked because the result set was tiny.
     */
    Page<SettlementAccount> search(SettlementAccountQuery query, PageRequest pageRequest);

    Optional<SettlementAccount> findByAccountId(String accountId);

    /**
     * Sets a new liquidity buffer on an existing account.
     *
     * <p>Task-based rather than a generic {@code save(account)}: a caller cannot
     * silently rewrite the BIC or the currency while "updating a buffer", and the
     * audit trail in Layer 8 can record what the caller actually intended.
     *
     * @return the updated account
     * @throws java.util.NoSuchElementException if no such account exists
     * @throws com.atlas.liquidity.common.money.CurrencyMismatchException
     *         if the buffer currency differs from the account currency
     */
    SettlementAccount updateLiquidityBuffer(String accountId, Money newBuffer);
}
