package com.atlas.liquidity.refdata.persistence;

import com.atlas.liquidity.common.money.CurrencyMismatchException;
import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The adapter: implements the domain's port using Hibernate and Postgres.
 *
 * <p>This is the only class that knows both worlds. Everything above it sees
 * immutable {@code SettlementAccount} records; everything below it sees JPA
 * entities. The translation happens here and nowhere else.
 *
 * <p><b>{@code @Repository} does more than mark a bean.</b> It also opts this
 * class into Spring's automatic <em>persistence exception translation</em>: a
 * post-processor wraps the bean in a proxy that converts vendor-specific
 * persistence failures into Spring's own {@code DataAccessException} hierarchy.
 * That is genuinely useful - it means the layers above never import a Hibernate
 * or JDBC exception type, so swapping Hibernate for JOOQ would not change a
 * single {@code catch} block anywhere else.
 *
 * <p><b>And it has a sharp edge we walked straight into.</b> Because the JPA
 * specification says {@code EntityManager} throws {@code IllegalArgumentException}
 * for API misuse, the translator maps <em>any</em>
 * {@code IllegalArgumentException} escaping this class into
 * {@code InvalidDataAccessApiUsageException}. Our own domain validation used to
 * throw {@code IllegalArgumentException} - and it was silently rewritten on the
 * way out, so the {@code @ExceptionHandler(IllegalArgumentException.class)} in
 * the web layer stopped matching and a validation failure came back as a
 * <b>500 instead of a 400</b>.
 *
 * <p>The lesson is worth more than the fix: <b>do not throw generic JDK
 * exceptions from a layer whose framework has claimed them.</b> Throw a domain
 * exception that says what actually went wrong - here
 * {@link CurrencyMismatchException} from {@code liquidity-common} - and nothing
 * can quietly reinterpret it. This is a good, specific answer to "tell me about
 * a bug that surprised you".
 *
 * <p><b>{@code @Transactional(readOnly = true)} on the class.</b> Read-only is
 * not decoration. It tells Hibernate to skip dirty-checking - it does not
 * snapshot every loaded entity or scan for changes at flush time - which is a
 * measurable saving on large result sets. It also lets the JDBC driver mark the
 * connection read-only, which some databases and most connection proxies use to
 * route queries to a read replica. Layer 11 depends on exactly that behaviour.
 * The write method below overrides it.
 *
 * <p><b>Where transaction boundaries belong.</b> Textbook layering puts
 * {@code @Transactional} on an application service, because a transaction
 * should span a complete business operation - and if you have two repository
 * calls that must succeed or fail together, a transaction on each one is
 * exactly wrong. We have no such orchestration yet, so a service class here
 * would be ceremony with no content, and the boundary sits on the adapter.
 * When Layer 5 needs to write a position and publish an event atomically, a
 * real service appears and the boundary moves up.
 */
@Repository
@Transactional(readOnly = true)
public class JpaSettlementAccountRepositoryAdapter implements SettlementAccountRepository {

    private final SettlementAccountJpaRepository jpaRepository;

    JpaSettlementAccountRepositoryAdapter(SettlementAccountJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<SettlementAccount> findAll() {
        return toDomain(jpaRepository.findAllByOrderByAccountId());
    }

    @Override
    public Optional<SettlementAccount> findByAccountId(String accountId) {
        return jpaRepository.findById(accountId).map(JpaSettlementAccountRepositoryAdapter::toDomain);
    }

    @Override
    public List<SettlementAccount> findByCurrency(String currencyCode) {
        // Normalised here so "usd" and "USD" behave the same. Note this is a
        // real query now, not a stream filter over everything in memory - the
        // Layer 1 implementation loaded all rows and filtered in Java, which is
        // fine over six seed accounts and indefensible over six million.
        String normalised = currencyCode.toUpperCase(Locale.ROOT);
        return toDomain(jpaRepository.findByCurrencyCodeOrderByAccountId(normalised));
    }

    @Override
    public List<SettlementAccount> findByJurisdiction(Jurisdiction jurisdiction) {
        return toDomain(jpaRepository.findByJurisdictionOrderByAccountId(jurisdiction));
    }

    @Override
    @Transactional
    public SettlementAccount updateLiquidityBuffer(String accountId, Money newBuffer) {
        SettlementAccountEntity entity = jpaRepository.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No settlement account found with id: " + accountId));

        // A domain exception, NOT IllegalArgumentException - see the class
        // Javadoc. Spring's exception translator rewrites the latter and would
        // turn this 400 into a 500.
        if (!entity.getCurrencyCode().equals(newBuffer.currencyCode())) {
            throw new CurrencyMismatchException(entity.getCurrencyCode(), newBuffer.currencyCode());
        }

        entity.changeLiquidityBufferAmount(newBuffer.amount());

        // NOTE: there is no jpaRepository.save(entity) call, and that is not an
        // omission. Inside a transaction, entities loaded through the
        // EntityManager are MANAGED: Hibernate holds a snapshot of their state
        // and, at commit, compares the snapshot with the current values and
        // issues an UPDATE for whatever changed. That is "automatic dirty
        // checking", and it is the single most surprising thing about JPA to
        // someone arriving from JDBC.
        //
        // The flip side is the trap: mutate a managed entity by accident -
        // inside a getter, in a mapping helper, anywhere - and you have silently
        // written to the database. "Why did my read endpoint issue an UPDATE"
        // is a genuine production mystery, and this is the answer.
        //
        // The @Version column means this UPDATE carries "AND version = ?". If
        // another transaction changed this row since we read it, zero rows match
        // and Spring raises OptimisticLockingFailureException.
        return toDomain(entity);
    }

    // --- mapping ---------------------------------------------------------

    private static List<SettlementAccount> toDomain(List<SettlementAccountEntity> entities) {
        return entities.stream().map(JpaSettlementAccountRepositoryAdapter::toDomain).toList();
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
