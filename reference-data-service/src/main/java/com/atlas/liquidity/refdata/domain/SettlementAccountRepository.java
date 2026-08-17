package com.atlas.liquidity.refdata.domain;

import java.util.List;
import java.util.Optional;

/**
 * Port through which the application reads settlement accounts.
 *
 * <p>The interface lives in the domain package; the implementation does not.
 * That inversion is the point: the domain declares what it needs, and the
 * outside world supplies it. In Layer 2 we swap the in-memory implementation for
 * a JPA/Oracle one and <em>nothing above this line changes</em> - no controller,
 * no test of business behaviour.
 *
 * <p>This is the Dependency Inversion Principle, and it is also the honest
 * answer to "how do you keep a service testable without a database".
 */
public interface SettlementAccountRepository {

    List<SettlementAccount> findAll();

    Optional<SettlementAccount> findByAccountId(String accountId);

    List<SettlementAccount> findByCurrency(String currencyCode);

    List<SettlementAccount> findByJurisdiction(Jurisdiction jurisdiction);
}
