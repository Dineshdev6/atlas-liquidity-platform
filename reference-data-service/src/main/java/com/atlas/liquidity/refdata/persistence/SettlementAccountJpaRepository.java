package com.atlas.liquidity.refdata.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data JPA repository for {@link SettlementAccountEntity}.
 *
 * <p><b>Layer 3 removed the three derived query methods and added one interface.</b>
 * {@code JpaSpecificationExecutor} contributes
 * {@code findAll(Specification, Pageable)} - one method that answers every
 * filter combination, sorted and paged, in a single SQL statement. The derived
 * methods it replaced ({@code findByCurrencyCodeOrderByAccountId} and friends)
 * were fine individually and could not be combined.
 *
 * <p>Notice how little is written here now. Spring Data generates the
 * implementation at startup: {@code JpaRepository} supplies CRUD,
 * {@code JpaSpecificationExecutor} supplies criteria-based querying. The
 * interesting code moved to {@code SettlementAccountSpecifications}, where the
 * business criteria actually live.
 *
 * <p>Still package-private, still returning entities. Nothing outside
 * {@code persistence} can see it, so Hibernate types cannot leak into the domain
 * or the API by accident.
 */
interface SettlementAccountJpaRepository
        extends JpaRepository<SettlementAccountEntity, String>,
                JpaSpecificationExecutor<SettlementAccountEntity> {
}
