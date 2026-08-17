package com.atlas.liquidity.refdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.liquidity.refdata.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end test: real HTTP, real Spring context, real Postgres.
 *
 * <p>Renamed from {@code ReferenceDataApplicationTest} in Layer 2. It now needs
 * a database, so it is an integration test and belongs to Failsafe rather than
 * Surefire. If you leave it named {@code ...Test}, {@code mvn test} tries to run
 * it and fails on any machine without Docker - including most CI unit-test
 * stages.
 *
 * <p>Keep the number of tests in here small. Their job is to prove the pieces
 * are wired together - context loads, Flyway ran, the filter is registered, the
 * endpoints answer - not to cover behaviour. Behaviour is covered far more
 * cheaply one level down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceDataApplicationIT extends AbstractPostgresIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("the accounts endpoint serves data that came out of Postgres")
    void accountsEndpointServesDatabaseRows() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/accounts"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("ACC-US-0001")
                .contains("\"residencyRegion\":\"us-east\"")
                // Money crosses the wire as a string, not a JSON number.
                .contains("\"liquidityBuffer\":\"25000000.00\"");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    @DisplayName("an unknown account is an RFC 7807 problem detail with 404")
    void unknownAccountIsProblemDetail() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/v1/accounts/ACC-NOPE"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Settlement account not found");
    }

    @Test
    @DisplayName("PUT updates the liquidity buffer and the change survives a re-read")
    void updatesLiquidityBufferOverHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"amount\":\"31000000.00\"}", headers);

        ResponseEntity<String> put = restTemplate.exchange(
                url("/api/v1/accounts/ACC-US-0002/liquidity-buffer"),
                HttpMethod.PUT, request, String.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).contains("\"liquidityBuffer\":\"31000000.00\"");

        ResponseEntity<String> reread =
                restTemplate.getForEntity(url("/api/v1/accounts/ACC-US-0002"), String.class);
        assertThat(reread.getBody()).contains("\"liquidityBuffer\":\"31000000.00\"");

        // PUT is idempotent - sending it again leaves the same state.
        ResponseEntity<String> again = restTemplate.exchange(
                url("/api/v1/accounts/ACC-US-0002/liquidity-buffer"),
                HttpMethod.PUT, request, String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).contains("\"liquidityBuffer\":\"31000000.00\"");
    }

    @Test
    @DisplayName("a malformed amount is rejected by Bean Validation with a field-level error")
    void rejectsMalformedAmount() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"amount\":\"not-a-number\"}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/accounts/ACC-US-0001/liquidity-buffer"),
                HttpMethod.PUT, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("Validation failed")
                .contains("amount");
    }

    @Test
    @DisplayName("actuator health reports the database, not just the process")
    void actuatorHealthIncludesDatabase() {
        // With a datasource on the classpath, Boot adds a DataSource health
        // indicator that issues a validation query. In Layer 10 this is what
        // makes the readiness probe honest: a pod that cannot reach Postgres
        // stops receiving traffic instead of returning 500s to users.
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
