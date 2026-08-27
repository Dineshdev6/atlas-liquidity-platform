package com.atlas.liquidity.position.api;

import com.atlas.liquidity.position.projection.AccountPositionJpaRepository;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the projection.
 *
 * <p>Read-only, and that is a design statement rather than a limitation. Nothing
 * outside this service may write a position: the only way the numbers change is
 * an event arriving on the topic. A POST here would let a caller put the
 * projection into a state no sequence of events could produce, and from then on
 * "rebuild it from history" would be a lie.
 *
 * <p>No pagination, unlike reference-data-service, and it is a deliberate
 * shortcut rather than an oversight - Layer 3's {@code Page} machinery lives in
 * {@code liquidity-common} and adding it here is mechanical. Left out to keep
 * this part about Kafka. It is on the list.
 */
@RestController
@RequestMapping(path = "/api/v1/positions", produces = MediaType.APPLICATION_JSON_VALUE)
public class PositionController {

    private final AccountPositionJpaRepository positions;

    PositionController(AccountPositionJpaRepository positions) {
        this.positions = positions;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PositionResponse> listPositions() {
        return positions.findAllByOrderByAccountIdAsc().stream()
                .map(PositionResponse::from)
                .toList();
    }

    /**
     * Fetches one position.
     *
     * <p>{@code ResponseEntity.of(Optional)} is 200-or-404 in one expression, and
     * it is worth knowing that it exists - it removes the most common reason
     * people write an exception class and a handler for it. Layer 3 has a full
     * RFC 7807 handler because its error contract is rich; here there is one
     * failure mode and it does not need ceremony. Matching the weight of the
     * machinery to the weight of the problem is a judgement worth showing.
     */
    @GetMapping("/{accountId}")
    @Transactional(readOnly = true)
    public ResponseEntity<PositionResponse> getPosition(@PathVariable String accountId) {
        return ResponseEntity.of(positions.findById(accountId).map(PositionResponse::from));
    }
}
