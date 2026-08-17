package com.atlas.liquidity.refdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Full-context integration test: the real application, on a real port, with the
 * real (in-memory) repository.
 *
 * <p>You want exactly a handful of these, not hundreds. Their job is to prove
 * the pieces are wired together - that the context loads, the filter is
 * registered, the actuator endpoints answer. The detailed behaviour is covered
 * far more cheaply by the slice test and the unit tests. That split - many fast
 * tests, few slow ones - is the test pyramid, and being able to justify it is
 * worth more in an interview than reciting the definition.
 *
 * <p>{@code webEnvironment = RANDOM_PORT} starts a real Tomcat on a free port,
 * so tests never collide with a service you left running on 8081 - and never
 * collide with each other on a CI agent running builds in parallel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceDataApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SettlementAccountRepository repository;

    @Test
    @DisplayName("the application context loads and wires the repository")
    void contextLoads() {
        // If component scanning, configuration or bean wiring is broken, this
        // test fails before its first assertion. It is a smoke test, and it
        // earns its place: it catches the class of error that unit tests cannot.
        assertThat(repository).isNotNull();
        assertThat(repository.findAll()).isNotEmpty();
    }

    @Test
    @DisplayName("seed data spans multiple residency regions")
    void seedDataSpansResidencyRegions() {
        assertThat(repository.findAll())
                .extracting(account -> account.jurisdiction().residencyRegion())
                .contains("us-east", "eu-central", "apac-southeast");
    }

    @Test
    @DisplayName("the repository filters by jurisdiction")
    void filtersByJurisdiction() {
        assertThat(repository.findByJurisdiction(Jurisdiction.US))
                .isNotEmpty()
                .allSatisfy(account -> assertThat(account.jurisdiction()).isEqualTo(Jurisdiction.US));
    }

    @Test
    @DisplayName("the accounts endpoint answers over real HTTP")
    void accountsEndpointRespondsOverHttp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/api/v1/accounts", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ACC-US-0001");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    @DisplayName("actuator reports the service as UP")
    void actuatorHealthIsUp() {
        // This is the exact endpoint Kubernetes will probe in Layer 10.
        // Asserting on it now means the readiness contract is never guesswork.
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
