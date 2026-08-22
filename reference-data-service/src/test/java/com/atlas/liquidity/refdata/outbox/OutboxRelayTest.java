package com.atlas.liquidity.refdata.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The scheduling wrapper, which has exactly two jobs: honour the disable switch,
 * and never let an exception escape.
 *
 * <p>Small, but both behaviours are ones that fail silently in production if you
 * get them wrong, and neither is visible from a test of the publisher.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxPublisher publisher;

    @Test
    @DisplayName("an enabled relay delegates to the publisher")
    void enabledRelayPublishes() {
        given(publisher.publishPending()).willReturn(3);

        new OutboxRelay(publisher, true).poll();

        // Through the injected bean, not a method on itself. That is what makes
        // the publisher's @Transactional actually apply - a self-invocation would
        // bypass the proxy and the annotation would be silently ignored.
        verify(publisher).publishPending();
    }

    @Test
    @DisplayName("a failing run never escapes the scheduled method")
    void failuresAreSwallowed() {
        given(publisher.publishPending()).willThrow(new IllegalStateException("database unreachable"));

        // A @Scheduled method that throws is not retried, and the framework logs
        // it somewhere you are not looking. Catching means a transient outage
        // costs one tick rather than stalling event publication until somebody
        // notices the backlog.
        assertThatCode(() -> new OutboxRelay(publisher, true).poll()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a disabled relay does nothing at all")
    void disabledRelayIsInert() {
        new OutboxRelay(publisher, false).poll();

        // Integration tests switch this off so they can assert on outbox rows
        // without racing a timer. Cheap to build, and it removes a whole class of
        // flaky test.
        verifyNoInteractions(publisher);
    }
}
