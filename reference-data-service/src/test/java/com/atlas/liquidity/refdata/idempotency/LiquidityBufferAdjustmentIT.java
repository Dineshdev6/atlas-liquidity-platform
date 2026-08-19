package com.atlas.liquidity.refdata.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.liquidity.refdata.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * The idempotency mechanism, end to end, against a real database.
 *
 * <p>These are the most important tests in Layer 3. The behaviour they pin down -
 * "a retried request must not apply the work twice" - is the difference between a
 * payments platform that reconciles and one that does not.
 *
 * <p>Every test generates its own key, because a key is single-use by definition and
 * the database is not thrown away between runs. {@code @AfterEach} restores the
 * buffer so tests cannot influence each other through shared state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiquidityBufferAdjustmentIT extends AbstractPostgresIntegrationTest {

    private static final String ACCOUNT = "ACC-GB-0001";
    private static final String BASELINE = "15000000.00";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @AfterEach
    void restoreBaseline() {
        // The absolute-set endpoint needs no key, which is itself the point: PUT is
        // naturally idempotent and can be used freely for exactly this reason.
        put("/api/v1/accounts/" + ACCOUNT + "/liquidity-buffer",
                "{\"amount\":\"" + BASELINE + "\"}");
    }

    // --- helpers ---------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> adjust(String key, String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set(IdempotencyService.HEADER, key);
        }
        return restTemplate.exchange(
                url("/api/v1/accounts/" + ACCOUNT + "/liquidity-buffer-adjustments"),
                HttpMethod.POST,
                new HttpEntity<>("{\"amount\":\"" + amount + "\",\"reason\":\"integration test\"}", headers),
                String.class);
    }

    private ResponseEntity<String> put(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private String currentBuffer() {
        String body = restTemplate.getForEntity(url("/api/v1/accounts/" + ACCOUNT), String.class).getBody();
        int start = body.indexOf("\"liquidityBuffer\":\"") + "\"liquidityBuffer\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // --- the core behaviour ----------------------------------------------

    @Test
    @DisplayName("a first adjustment applies and is not marked as a replay")
    void firstAdjustmentApplies() {
        ResponseEntity<String> response = adjust(newKey(), "5000000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(response.getBody()).contains("\"liquidityBuffer\":\"20000000.00\"");
        assertThat(currentBuffer()).isEqualTo("20000000.00");
    }

    /**
     * The test this whole layer exists for.
     *
     * <p>Without an idempotency key, the second call would add another five million
     * and the buffer would read 25,000,000 - and nothing would ever tell you. With
     * the key, the second call returns the first call's response and touches nothing.
     */
    @Test
    @DisplayName("retrying with the same key returns the original response and does NOT apply the work twice")
    void sameKeyIsAppliedOnce() {
        String key = newKey();

        ResponseEntity<String> first = adjust(key, "5000000.00");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(currentBuffer()).isEqualTo("20000000.00");

        // The retry a real client would send after a timeout.
        ResponseEntity<String> retry = adjust(key, "5000000.00");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        // Byte-identical response, because it IS the stored response - deserialised
        // from the record and returned. Note this only works without Jackson
        // annotations on the DTO because the build compiles with -parameters, the
        // same flag we had to add in Layer 1.
        assertThat(retry.getBody()).isEqualTo(first.getBody());

        // And the money moved exactly once.
        assertThat(currentBuffer()).isEqualTo("20000000.00");
    }

    @Test
    @DisplayName("ten retries with the same key still apply the work once")
    void manyRetriesStillApplyOnce() {
        String key = newKey();

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(adjust(key, "1000000.00").getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(currentBuffer()).isEqualTo("16000000.00");
    }

    @Test
    @DisplayName("a different key applies the work again - the key is the guard, not the payload")
    void differentKeyAppliesAgain() {
        // This is the correct behaviour and worth being explicit about. Two genuinely
        // separate adjustments of the same size are legitimate; deduplicating on
        // payload alone would silently swallow the second one, which would be a much
        // worse bug than the one we are preventing.
        adjust(newKey(), "1000000.00");
        adjust(newKey(), "1000000.00");

        assertThat(currentBuffer()).isEqualTo("17000000.00");
    }

    @Test
    @DisplayName("reusing a key with a different payload is a 422, not a silent replay")
    void keyReuseWithDifferentPayloadIsRejected() {
        String key = newKey();
        adjust(key, "5000000.00");

        ResponseEntity<String> reused = adjust(key, "50000000.00");

        // 422, not 400: the request is syntactically perfect and semantically
        // impossible. And an error rather than a replay, because a client that got
        // the first response back would believe its fifty million had landed.
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody())
                .contains("Idempotency key reused")
                .contains("Use a new key");

        // Nothing was applied for the second request.
        assertThat(currentBuffer()).isEqualTo("20000000.00");
    }

    // --- signed adjustments and guards -----------------------------------

    @Test
    @DisplayName("a negative adjustment reduces the buffer")
    void negativeAdjustmentReduces() {
        ResponseEntity<String> response = adjust(newKey(), "-5000000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(currentBuffer()).isEqualTo("10000000.00");
    }

    @Test
    @DisplayName("an adjustment that would take the buffer below zero is refused")
    void refusesNegativeResult() {
        ResponseEntity<String> response = adjust(newKey(), "-99000000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("below zero");

        // Refused before anything was written.
        assertThat(currentBuffer()).isEqualTo(BASELINE);
    }

    @Test
    @DisplayName("a refused adjustment does not burn the idempotency key")
    void failedAdjustmentDoesNotBurnTheKey() {
        // Both halves commit or neither does. Because the key record and the business
        // work share one transaction, a rejected adjustment rolls the key record back
        // too - so the client can fix its request and reuse the key. If the key were
        // recorded in its own transaction, the client would be permanently locked out
        // of that key with nothing applied, which is the worst of both worlds.
        String key = newKey();

        assertThat(adjust(key, "-99000000.00").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> corrected = adjust(key, "1000000.00");
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(corrected.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("false");
        assertThat(currentBuffer()).isEqualTo("16000000.00");
    }

    // --- key validation --------------------------------------------------

    @Test
    @DisplayName("a missing Idempotency-Key header is a 400")
    void missingKeyIsBadRequest() {
        // Handled by ResponseEntityExceptionHandler (MissingRequestHeaderException),
        // which is another thing that would have been a 500 before Layer 3 part 1.
        ResponseEntity<String> response = adjust(null, "1000000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(currentBuffer()).isEqualTo(BASELINE);
    }

    @Test
    @DisplayName("a blank Idempotency-Key header is a 400")
    void blankKeyIsBadRequest() {
        assertThat(adjust("   ", "1000000.00").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an over-long key is a 400 rather than a database error")
    void overLongKeyIsBadRequest() {
        // The column is VARCHAR(128). Without the length check this would reach
        // Postgres and come back as a 409 data-integrity violation, which tells the
        // caller nothing useful.
        assertThat(adjust("k".repeat(200), "1000000.00").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a malformed amount is rejected before the key is claimed")
    void malformedAmountIsRejected() {
        String key = newKey();

        assertThat(adjust(key, "not-a-number").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Bean Validation runs before the controller body, so the key was never used
        // and remains available.
        assertThat(adjust(key, "1000000.00").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("adjusting an unknown account is a 404")
    void unknownAccountIsNotFound() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(IdempotencyService.HEADER, newKey());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/accounts/ACC-NOPE/liquidity-buffer-adjustments"),
                HttpMethod.POST,
                new HttpEntity<>("{\"amount\":\"1000.00\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- OpenAPI ---------------------------------------------------------

    @Test
    @DisplayName("the OpenAPI document is generated from the real mappings")
    void openApiDocumentIsPublished() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/v3/api-docs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("Atlas Liquidity Platform")
                .contains("/api/v1/accounts")
                .contains("liquidity-buffer-adjustments")
                .contains("Idempotency-Key");
    }

    @Test
    @DisplayName("Swagger UI is served")
    void swaggerUiIsServed() {
        // springdoc redirects /swagger-ui.html to /swagger-ui/index.html, and
        // TestRestTemplate follows it.
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/swagger-ui/index.html"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
