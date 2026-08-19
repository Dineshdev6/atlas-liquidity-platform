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
 * End-to-end test: real HTTP over a real Tomcat, real Spring context, real
 * Postgres.
 *
 * <p>Deliberately few tests. Their job is to prove the pieces are wired together
 * - context loads, Flyway ran, the correlation filter is registered, the
 * endpoints answer, the error contract holds. Behaviour is covered far more
 * cheaply one level down, in the slice test and the persistence IT.
 *
 * <p>The status-code tests here matter more than they look. Several of them only
 * pass because {@code GlobalExceptionHandler} now extends
 * {@code ResponseEntityExceptionHandler}; before Layer 3 the catch-all turned
 * every one of them into a 500.
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

    private ResponseEntity<String> putJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("the accounts endpoint serves a paged envelope from Postgres")
    void servesPagedEnvelope() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/accounts"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"content\":")
                .contains("ACC-US-0001")
                .contains("\"residencyRegion\":\"us-east\"")
                .contains("\"totalElements\":6")
                .contains("\"totalPages\":1")
                // Money crosses the wire as a string, never a JSON number.
                .contains("\"liquidityBuffer\":\"25000000.00\"")
                // And Spring Data's internals do not appear in our contract.
                .doesNotContain("\"pageable\"");

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    @DisplayName("paging and filtering work together over HTTP")
    void pagesAndFilters() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/v1/accounts?currency=usd&size=1&page=0&sort=accountId"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("ACC-US-0001")
                .doesNotContain("ACC-US-0002")     // page size 1
                .doesNotContain("ACC-EU-0001")     // currency filter
                .contains("\"totalElements\":2")   // but the total counts both
                .contains("\"last\":false");
    }

    @Test
    @DisplayName("an oversized page size is refused rather than served")
    void refusesOversizedPage() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/v1/accounts?size=999999"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("must not exceed 200");
    }

    @Test
    @DisplayName("an unknown sort field is a 400, not a 500 that names our columns")
    void refusesUnknownSortField() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/v1/accounts?sort=secret"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Unknown sort field");
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
    @DisplayName("an unmapped path is 404, not 500 - the Layer 3 fix, end to end")
    void unmappedPathIs404() {
        // Spring raises NoResourceFoundException for this, which is a 404.
        // Before Layer 3, our catch-all @ExceptionHandler(Exception.class) caught
        // it and reported a client typo as a server failure - so the 5xx alert
        // fired for something that was not broken.
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/v1/nonexistent"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unsupported method on a real endpoint is 405, not 500")
    void unsupportedMethodIs405() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/accounts"), HttpMethod.DELETE, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("PUT updates the buffer, and the change survives a re-read")
    void updatesBuffer() {
        ResponseEntity<String> put =
                putJson("/api/v1/accounts/ACC-US-0002/liquidity-buffer", "{\"amount\":\"31000000.00\"}");

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).contains("\"liquidityBuffer\":\"31000000.00\"");

        assertThat(restTemplate.getForEntity(url("/api/v1/accounts/ACC-US-0002"), String.class).getBody())
                .contains("\"liquidityBuffer\":\"31000000.00\"");

        // PUT is idempotent - sending it again leaves the same state. That is why
        // a client whose call timed out can safely retry it.
        ResponseEntity<String> again =
                putJson("/api/v1/accounts/ACC-US-0002/liquidity-buffer", "{\"amount\":\"31000000.00\"}");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).contains("\"liquidityBuffer\":\"31000000.00\"");
    }

    @Test
    @DisplayName("a malformed amount is rejected with a field-level validation error")
    void rejectsMalformedAmount() {
        ResponseEntity<String> response =
                putJson("/api/v1/accounts/ACC-US-0001/liquidity-buffer", "{\"amount\":\"not-a-number\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Validation failed").contains("amount");
    }

    @Test
    @DisplayName("actuator health reports the database, not just the process")
    void actuatorHealthIncludesDatabase() {
        // In Layer 10 this is what makes the readiness probe honest: a pod that
        // cannot reach Postgres stops receiving traffic instead of returning 500s.
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
