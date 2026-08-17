package com.atlas.liquidity.refdata.persistence;

import com.atlas.liquidity.refdata.domain.Jurisdiction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link SettlementAccountEntity}.
 *
 * <p>You write no implementation. At startup Spring Data generates a proxy that
 * parses each method NAME and derives the query from it -
 * {@code findByCurrencyCodeOrderByAccountId} becomes
 * {@code select e from SettlementAccountEntity e where e.currencyCode = ?1 order by e.accountId}.
 *
 * <p>Two consequences worth knowing:
 *
 * <p><b>Typos are startup failures, not runtime failures.</b> Write
 * {@code findByCurencyCode} and the context refuses to start with "No property
 * 'curencyCode' found". That is the good kind of failure - loud, immediate,
 * and impossible to deploy past.
 *
 * <p><b>Derived queries stop scaling.</b> They are excellent for two or three
 * criteria and become unreadable beyond that -
 * {@code findByCurrencyCodeAndJurisdictionAndLegalEntityOrderByAccountIdDesc}
 * is a method name nobody should have to read. At that point switch to
 * {@code @Query} with explicit JPQL, or to the Criteria API / Specifications
 * for genuinely dynamic filters. Knowing where that line is, and saying so,
 * reads as experience.
 *
 * <p>Note this interface is package-private and returns ENTITIES. Nothing
 * outside {@code persistence} can see it, so Hibernate types cannot leak into
 * the domain or the API by accident. The only public door into this package is
 * {@link JpaSettlementAccountRepositoryAdapter}.
 */
interface SettlementAccountJpaRepository extends JpaRepository<SettlementAccountEntity, String> {

    List<SettlementAccountEntity> findAllByOrderByAccountId();

    List<SettlementAccountEntity> findByCurrencyCodeOrderByAccountId(String currencyCode);

    List<SettlementAccountEntity> findByJurisdictionOrderByAccountId(Jurisdiction jurisdiction);
}
