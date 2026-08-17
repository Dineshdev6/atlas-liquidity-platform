package com.atlas.liquidity.refdata.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Layer 1 implementation: a fixed set of seed accounts held in memory.
 *
 * <p>Deliberately temporary. Layer 2 replaces this with Flyway-managed schema
 * and a Spring Data JPA repository against Postgres (standing in for Oracle),
 * verified with Testcontainers. Keeping the seed data here now means the REST
 * layer, the error handling and the tests are all real from day one, without
 * waiting on a database.
 *
 * <p>The seed set is chosen to span three residency regions, so that the
 * multi-region work in Layer 11 has something meaningful to route.
 */
@Repository
public class InMemorySettlementAccountRepository implements SettlementAccountRepository {

    private final Map<String, SettlementAccount> accountsById;

    public InMemorySettlementAccountRepository() {
        this(seedAccounts());
    }

    /** Test-friendly constructor: lets a test supply its own fixture set. */
    public InMemorySettlementAccountRepository(List<SettlementAccount> accounts) {
        Map<String, SettlementAccount> index = new LinkedHashMap<>();
        accounts.forEach(account -> index.put(account.accountId(), account));
        this.accountsById = Map.copyOf(index);
    }

    @Override
    public List<SettlementAccount> findAll() {
        return accountsById.values().stream()
                .sorted(Comparator.comparing(SettlementAccount::accountId))
                .toList();
    }

    @Override
    public Optional<SettlementAccount> findByAccountId(String accountId) {
        return Optional.ofNullable(accountsById.get(accountId));
    }

    @Override
    public List<SettlementAccount> findByCurrency(String currencyCode) {
        String normalised = currencyCode.toUpperCase(Locale.ROOT);
        return findAll().stream()
                .filter(account -> account.currencyCode().equals(normalised))
                .toList();
    }

    @Override
    public List<SettlementAccount> findByJurisdiction(Jurisdiction jurisdiction) {
        return findAll().stream()
                .filter(account -> account.jurisdiction() == jurisdiction)
                .toList();
    }

    private static List<SettlementAccount> seedAccounts() {
        return List.of(
                new SettlementAccount("ACC-US-0001", "8801234567", "ATLAS-BANK-NA", "USD", Jurisdiction.US, "ATLBUS33XXX"),
                new SettlementAccount("ACC-US-0002", "8801234568", "ATLAS-BANK-NA", "USD", Jurisdiction.US, "FRNYUS33XXX"),
                new SettlementAccount("ACC-GB-0001", "4409876543", "ATLAS-BANK-UK", "GBP", Jurisdiction.UK, "ATLBGB2LXXX"),
                new SettlementAccount("ACC-EU-0001", "DE89370400440532013000", "ATLAS-BANK-EU", "EUR", Jurisdiction.EU, "ATLBDEFFXXX"),
                new SettlementAccount("ACC-SG-0001", "6501122334", "ATLAS-BANK-APAC", "SGD", Jurisdiction.SG, "ATLBSGSGXXX"),
                new SettlementAccount("ACC-JP-0001", "8102233445", "ATLAS-BANK-APAC", "JPY", Jurisdiction.HK, "ATLBHKHHXXX"));
    }
}
