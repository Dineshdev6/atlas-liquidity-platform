package com.atlas.liquidity.refdata.persistence;

import com.atlas.liquidity.refdata.domain.Jurisdiction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * JPA mapping for the {@code settlement_account} table.
 *
 * <p>This class exists to talk to Hibernate and nothing else. It is mutable,
 * has a no-arg constructor, and enforces no business rules - because JPA
 * requires all three. Every business invariant lives in the immutable
 * {@code SettlementAccount} record in the domain package, which this class is
 * translated into by {@code JpaSettlementAccountRepositoryAdapter}.
 *
 * <p>Things in here worth being able to explain:
 *
 * <p><b>{@code @Enumerated(EnumType.STRING)}.</b> Never {@code ORDINAL}, which
 * is the default. Ordinal stores the enum's position as an integer, so
 * inserting a new constant in the middle of {@code Jurisdiction} silently
 * relabels every existing row - EU data becomes Singapore data. That is a
 * catastrophic, silent, hard-to-detect corruption, and it is a genuinely common
 * production incident. Always {@code STRING}.
 *
 * <p><b>{@code @Version}.</b> Optimistic locking. Hibernate appends
 * {@code AND version = ?} to every UPDATE and bumps the value. If two
 * transactions read the same row and both write, the second one updates zero
 * rows and Hibernate raises {@code OptimisticLockingFailureException} rather
 * than letting one silently overwrite the other. The alternative, pessimistic
 * locking ({@code SELECT ... FOR UPDATE}), holds a real database lock for the
 * whole transaction - correct when contention is high, expensive when it is
 * not. Reference data is read-heavy and rarely contended, so optimistic is
 * right here. Be ready to argue the choice both ways.
 *
 * <p><b>No {@code @GeneratedValue}.</b> {@code accountId} is a meaningful
 * business identifier assigned upstream, not a surrogate key. That is a real
 * decision with consequences - Hibernate cannot tell a new entity from a
 * detached one by looking at a non-null assigned id, which is why this class
 * never relies on {@code save()} to decide between INSERT and UPDATE.
 *
 * <p><b>No {@code equals}/{@code hashCode}.</b> Implementing them on a JPA
 * entity is a well-known minefield (identity changes when the row is persisted,
 * lazy proxies compare unequal to their targets, sets behave differently before
 * and after a flush). We never put entities in a Set or compare them, so the
 * default identity semantics are correct and honest.
 */
@Entity
@Table(name = "settlement_account")
public class SettlementAccountEntity {

    @Id
    @Column(name = "account_id", nullable = false, length = 32)
    private String accountId;

    @Column(name = "account_number", nullable = false, length = 64)
    private String accountNumber;

    @Column(name = "legal_entity", nullable = false, length = 64)
    private String legalEntity;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "jurisdiction", nullable = false, length = 8)
    private Jurisdiction jurisdiction;

    @Column(name = "bic", length = 11)
    private String bic;

    @Column(name = "liquidity_buffer_amount", nullable = false, precision = 23, scale = 4)
    private BigDecimal liquidityBufferAmount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Owned by the database (DEFAULT now()). Mapped read-only so Hibernate
    // reports it but never tries to write it.
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** Required by JPA. Not for application use. */
    protected SettlementAccountEntity() {
    }

    public String getAccountId() {
        return accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getLegalEntity() {
        return legalEntity;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public Jurisdiction getJurisdiction() {
        return jurisdiction;
    }

    public String getBic() {
        return bic;
    }

    public BigDecimal getLiquidityBufferAmount() {
        return liquidityBufferAmount;
    }

    public long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * The only mutator on this class, and the only field Layer 2 ever changes.
     *
     * <p>A setter per field would let any caller mutate anything. One named
     * method that changes one field is the persistence-layer echo of the
     * task-based operation on the repository port. Narrow surfaces are cheap to
     * reason about and cheap to audit.
     */
    void changeLiquidityBufferAmount(BigDecimal newAmount) {
        this.liquidityBufferAmount = newAmount;
    }
}
