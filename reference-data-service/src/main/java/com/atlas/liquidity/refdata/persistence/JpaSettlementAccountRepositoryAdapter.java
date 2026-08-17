package com.atlas.liquidity.refdata.persistence;

import com.atlas.liquidity.common.money.CurrencyMismatchException;
import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.common.query.Page;
import com.atlas.liquidity.common.query.PageRequest;
import com.atlas.liquidity.common.query.SortDirection;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import java.util.Currency;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The adapter: implements the domain's port using Hibernate and Postgres.
 *
 * <p>This is the only class that knows both worlds. Everything above it sees
 * immutable records and our own {@code Page}; everything below it sees JPA
 * entities, {@code Specification} and Spring Data's {@code Pageable}. The
 * translation happens here and nowhere else - which is why the domain port
 * mentions no framework type at all.
 *
 * <p><b>{@code @Repository} does more than mark a bean.</b> It also opts this
 * class into Spring's automatic persistence exception translation, converting
 * vendor-specific failures into Spring's {@code DataAccessException} hierarchy so
 * the layers above never import a Hibernate type.
 *
 * <p><b>And it has a sharp edge we walked into in Layer 2.</b> Because the JPA
 * specification says {@code EntityManager} throws {@code IllegalArgumentException}
 * for API misuse, the translator rewrites <em>any</em>
 * {@code IllegalArgumentException} escaping this class into
 * {@code InvalidDataAccessApiUsageException}. Our currency check used to throw
 * the former, so it stopped matching the web layer's handler and came back as a
 * <b>500 instead of a 400</b>. The fix - visible below - is to throw a domain
 * exception the framework has no opinion about. The lesson generalises: inside a
 * framework's territory, generic JDK exceptions are not yours.
 *
 * <p><b>{@code @Transactional(readOnly = true)} on the class.</b> Hibernate skips
 * dirty-checking, so it does not snapshot loaded entities or scan for changes at
 * flush - a measurable saving on large result sets. It also lets the driver mark
 * the connection read-only, which connection proxies use to route to a read
 * replica. Layer 11 depends on that. The write method overrides it.
 */
@Repository
@Transactional(readOnly = true)
public class JpaSettlementAccountRepositoryAdapter implements SettlementAccountRepository {

    private final SettlementAccountJpaRepository jpaRepository;

    JpaSettlementAccountRepositoryAdapter(SettlementAccountJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    /**
     * One page of accounts matching the criteria.
     *
     * <p><b>What Spring Data actually issues here is two statements, not one.</b>
     * A {@code SELECT ... LIMIT ? OFFSET ?} for the rows, and a
     * {@code SELECT count(*)} for {@code totalElements}. That second query is not
     * free - on a large table it may scan far more than the page you asked for,
     * and it is the usual reason a paginated endpoint is slower than expected.
     *
     * <p>The alternatives are worth knowing: return only "is there a next page"
     * (fetch {@code size + 1} rows and look at the overflow, no count at all), or
     * switch to keyset pagination. A UI that renders "page 5 of 400" needs the
     * count; an infinite scroll does not. We keep the count because a total is
     * genuinely useful to an operations user, and because six accounts make it
     * free - but the cost is real and you should be able to name it.
     */
    @Override
    public Page<SettlementAccount> search(SettlementAccountQuery query, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest springPageRequest =
                org.springframework.data.domain.PageRequest.of(
                        pageRequest.page(), pageRequest.size(), toSort(pageRequest));

        org.springframework.data.domain.Page<SettlementAccountEntity> result =
                jpaRepository.findAll(SettlementAccountSpecifications.matching(query), springPageRequest);

        return Page.of(
                result.getContent().stream().map(JpaSettlementAccountRepositoryAdapter::toDomain).toList(),
                pageRequest,
                result.getTotalElements());
    }

    @Override
    public Optional<SettlementAccount> findByAccountId(String accountId) {
        return jpaRepository.findById(accountId).map(JpaSettlementAccountRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public SettlementAccount updateLiquidityBuffer(String accountId, Money newBuffer) {
        SettlementAccountEntity entity = jpaRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No settlement account found with id: " + accountId));

        // A domain exception, NOT IllegalArgumentException - see the class Javadoc.
        if (!entity.getCurrencyCode().equals(newBuffer.currencyCode())) {
            throw new CurrencyMismatchException(entity.getCurrencyCode(), newBuffer.currencyCode());
        }

        entity.changeLiquidityBufferAmount(newBuffer.amount());

        // NOTE: no jpaRepository.save(entity), and that is not an omission.
        // Inside a transaction, entities loaded through the EntityManager are
        // MANAGED: Hibernate snapshots them on load and, at commit, compares the
        // snapshot with current values and issues an UPDATE for whatever changed.
        // That is automatic dirty checking.
        //
        // The trap is the same mechanism: mutate a managed entity anywhere - in a
        // getter, in a mapping helper - and you have silently written to the
        // database. "Why did my read endpoint issue an UPDATE" has this as its
        // answer.
        //
        // The @Version column means this UPDATE carries "AND version = ?". If
        // another transaction changed the row since we read it, zero rows match
        // and Spring raises OptimisticLockingFailureException, which the web
        // layer turns into a 409.
        return toDomain(entity);
    }

    // --- translation ------------------------------------------------------

    /**
     * Turns our {@code PageRequest} into Spring Data's {@code Sort}.
     *
     * <p>{@code sortBy} is trusted here because the web layer resolved it through
     * {@code SettlementAccountSortField}, which is an allow-list. That ordering
     * matters: validate at the edge, and the inside stays simple. If an
     * unvalidated string reached this line, Spring Data would throw
     * {@code PropertyReferenceException} - a 500 whose message enumerates your
     * entity's real property names to whoever sent the request.
     */
    private static Sort toSort(PageRequest pageRequest) {
        Sort.Direction direction = pageRequest.direction() == SortDirection.DESC
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        Sort sort = Sort.by(direction, pageRequest.sortBy());

        // A tie-break on the primary key makes the ordering TOTAL. Without it,
        // sorting by a non-unique column (currency, say) leaves rows with equal
        // values in an order the database is free to change between queries - so
        // a row can appear on both page 1 and page 2, or on neither. That is a
        // genuinely nasty, intermittent paging bug, and this one line prevents it.
        if (!"accountId".equals(pageRequest.sortBy())) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "accountId"));
        }
        return sort;
    }

    private static SettlementAccount toDomain(SettlementAccountEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrencyCode());
        return new SettlementAccount(
                entity.getAccountId(),
                entity.getAccountNumber(),
                entity.getLegalEntity(),
                entity.getCurrencyCode(),
                entity.getJurisdiction(),
                entity.getBic(),
                Money.of(currency, entity.getLiquidityBufferAmount()));
    }
}
