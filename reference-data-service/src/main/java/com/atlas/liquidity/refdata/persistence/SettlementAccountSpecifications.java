package com.atlas.liquidity.refdata.persistence;

import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds a JPA {@link Specification} from the domain's filter criteria.
 *
 * <p>A {@code Specification} is a lambda that receives the query root and a
 * {@link jakarta.persistence.criteria.CriteriaBuilder} and returns a
 * {@link Predicate}. Spring Data composes it into the generated query. The
 * important consequence: <b>values are always bound as JDBC parameters</b>, never
 * concatenated into SQL text. Even if a caller sends
 * {@code ?currency=USD' OR '1'='1}, it arrives as a bound string and matches
 * nothing. SQL injection is structurally impossible here, not merely guarded
 * against - which is a much better answer than "we escape input".
 *
 * <p><b>Why one Specification instead of eight query methods.</b> Three optional
 * filters means eight possible combinations; four means sixteen. Derived query
 * methods need one per combination. This builds exactly the predicates the caller
 * asked for and no more, so adding a filter is one extra {@code if}.
 *
 * <p><b>Note the string property names</b> - {@code root.get("currencyCode")}.
 * That is the honest weakness of this approach: rename a field on the entity and
 * this compiles fine and fails at runtime. The fix is the JPA static metamodel
 * ({@code SettlementAccountEntity_.currencyCode}), which an annotation processor
 * generates and which is compile-time safe. We are not adding the processor for
 * three fields, but knowing the trade-off exists - and naming the metamodel as
 * the answer - is worth more than pretending strings are fine.
 */
final class SettlementAccountSpecifications {

    private SettlementAccountSpecifications() {
    }

    static Specification<SettlementAccountEntity> matching(SettlementAccountQuery query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>(3);

            if (query.currencyCode() != null) {
                predicates.add(criteriaBuilder.equal(root.get("currencyCode"), query.currencyCode()));
            }
            if (query.jurisdiction() != null) {
                // The entity maps this with @Enumerated(STRING), so Hibernate
                // binds the enum's name. Comparing against the enum rather than
                // a string keeps the type safety we have.
                predicates.add(criteriaBuilder.equal(root.get("jurisdiction"), query.jurisdiction()));
            }
            if (query.legalEntity() != null) {
                predicates.add(criteriaBuilder.equal(root.get("legalEntity"), query.legalEntity()));
            }

            // and() over an empty array is a conjunction that is always true, so
            // an unfiltered query needs no special case.
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
