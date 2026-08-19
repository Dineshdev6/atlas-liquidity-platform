package com.atlas.liquidity.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.common.query.Page;
import com.atlas.liquidity.common.query.PageRequest;
import com.atlas.liquidity.common.query.SortDirection;
import com.atlas.liquidity.common.web.CorrelationIdFilter;
import com.atlas.liquidity.refdata.api.SettlementAccountController;
import com.atlas.liquidity.refdata.api.SettlementAccountResponse;
import com.atlas.liquidity.refdata.application.LiquidityBufferAdjustmentService;
import com.atlas.liquidity.refdata.config.WebConfig;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.idempotency.IdempotencyExceptions;
import com.atlas.liquidity.refdata.idempotency.IdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test. No database, no Docker, about a second to run.
 *
 * <p>The collaborators are mocked, so these tests are about the <em>boundary</em>:
 * what the controller accepts, what it rejects, what it passes down, and what
 * shape it returns. Whether the query actually works against Postgres is proved
 * once, slowly, in {@code SettlementAccountPersistenceIT}, and whether the
 * idempotency mechanism actually prevents double-application is proved against a
 * real database in {@code LiquidityBufferAdjustmentIT}.
 *
 * <p><b>Why three {@code @MockitoBean}s and not one.</b> {@code @WebMvcTest} builds
 * a cut-down application context containing the web layer only - controllers,
 * converters, filters, the exception handler. {@code @Service} and
 * {@code @Repository} beans are deliberately <em>not</em> component-scanned, because
 * the whole point of a slice is to run in a second without a database. So every
 * constructor dependency of the controller under test must be supplied here by
 * hand. Add a dependency to the controller and forget to add the mock, and the
 * context fails to build with {@code NoSuchBeanDefinitionException} - which then
 * cascades into "ApplicationContext failure threshold (1) exceeded" on every
 * remaining test in the class, because Spring caches the failure rather than
 * retrying a context it already knows is broken. That cascade is why 22 tests can
 * all error from one missing line, and why the first stack trace in
 * {@code target/surefire-reports} is the only one worth reading.
 */
@WebMvcTest(SettlementAccountController.class)
@Import(WebConfig.class)
class SettlementAccountControllerTest {

    private static final SettlementAccount US_ACCOUNT = new SettlementAccount(
            "ACC-US-0001", "8801234567", "ATLAS-BANK-NA", "USD", Jurisdiction.US, "ATLBUS33XXX",
            Money.of("USD", "25000000.00"));

    private static final SettlementAccount EU_ACCOUNT = new SettlementAccount(
            "ACC-EU-0001", "DE89370400440532013000", "ATLAS-BANK-EU", "EUR", Jurisdiction.EU, "ATLBDEFFXXX",
            Money.of("EUR", "18000000.00"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementAccountRepository repository;

    @MockitoBean
    private LiquidityBufferAdjustmentService adjustmentService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    private void givenSearchReturns(List<SettlementAccount> accounts, long total) {
        given(repository.search(any(SettlementAccountQuery.class), any(PageRequest.class)))
                .willReturn(Page.of(accounts, PageRequest.firstPage("accountId"), total));
    }

    // --- response shape --------------------------------------------------

    @Nested
    @DisplayName("paged response")
    class PagedResponse {

        @Test
        @DisplayName("wraps rows in content, with paging metadata in a nested page object")
        void wrapsRowsWithMetadata() throws Exception {
            givenSearchReturns(List.of(US_ACCOUNT, EU_ACCOUNT), 2);

            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].accountId").value("ACC-US-0001"))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.size").value(20))
                    .andExpect(jsonPath("$.page.totalElements").value(2))
                    .andExpect(jsonPath("$.page.totalPages").value(1))
                    .andExpect(jsonPath("$.page.first").value(true))
                    .andExpect(jsonPath("$.page.last").value(true));
        }

        @Test
        @DisplayName("does not leak Spring Data's pageable internals")
        void doesNotLeakSpringDataInternals() throws Exception {
            // Serialising Spring's PageImpl emits a "pageable" object containing
            // offset, paged, unpaged and a nested sort - implementation detail
            // that becomes part of your public contract by accident, and that
            // moves when you upgrade Spring Data. Our own envelope cannot.
            givenSearchReturns(List.of(US_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageable").doesNotExist())
                    .andExpect(jsonPath("$.numberOfElements").doesNotExist())
                    .andExpect(jsonPath("$.sort").doesNotExist());
        }

        @Test
        @DisplayName("serialises the liquidity buffer as a string, not a JSON number")
        void serialisesMoneyAsString() throws Exception {
            // A JSON number is parsed as an IEEE-754 double by every JavaScript
            // client, which silently loses precision above ~15 significant digits.
            givenSearchReturns(List.of(US_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(jsonPath("$.content[0].liquidityBuffer").isString())
                    .andExpect(jsonPath("$.content[0].liquidityBuffer").value("25000000.00"));
        }

        @Test
        @DisplayName("exposes the data-residency region derived from jurisdiction")
        void exposesResidencyRegion() throws Exception {
            givenSearchReturns(List.of(EU_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(jsonPath("$.content[0].jurisdiction").value("EU"))
                    .andExpect(jsonPath("$.content[0].residencyRegion").value("eu-central"));
        }
    }

    // --- what reaches the repository -------------------------------------

    @Nested
    @DisplayName("query parameters")
    class QueryParameters {

        @Test
        @DisplayName("applies sane defaults when the caller supplies nothing")
        void appliesDefaults() throws Exception {
            givenSearchReturns(List.of(US_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isOk());

            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            org.mockito.Mockito.verify(repository).search(any(SettlementAccountQuery.class), captor.capture());

            PageRequest actual = captor.getValue();
            assertThat(actual.page()).isZero();
            assertThat(actual.size()).isEqualTo(20);
            assertThat(actual.sortBy()).isEqualTo("accountId");
            assertThat(actual.direction()).isEqualTo(SortDirection.ASC);
        }

        @Test
        @DisplayName("passes all three filters down as one query object")
        void passesFiltersDown() throws Exception {
            // The Layer 2 defect was fetching by currency and then filtering by
            // jurisdiction in Java. This asserts that both criteria now travel
            // together, so the database can answer in one statement.
            givenSearchReturns(List.of(US_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts")
                            .param("currency", "usd")
                            .param("jurisdiction", "us")
                            .param("legalEntity", "ATLAS-BANK-NA"))
                    .andExpect(status().isOk());

            ArgumentCaptor<SettlementAccountQuery> captor =
                    ArgumentCaptor.forClass(SettlementAccountQuery.class);
            org.mockito.Mockito.verify(repository).search(captor.capture(), any(PageRequest.class));

            SettlementAccountQuery query = captor.getValue();
            assertThat(query.currencyCode()).isEqualTo("USD");          // upper-cased
            assertThat(query.jurisdiction()).isEqualTo(Jurisdiction.US); // parsed
            assertThat(query.legalEntity()).isEqualTo("ATLAS-BANK-NA");
        }

        @Test
        @DisplayName("treats a blank filter as absent, not as an empty-string match")
        void blankFilterMeansAbsent() throws Exception {
            givenSearchReturns(List.of(US_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts").param("currency", "  "))
                    .andExpect(status().isOk());

            ArgumentCaptor<SettlementAccountQuery> captor =
                    ArgumentCaptor.forClass(SettlementAccountQuery.class);
            org.mockito.Mockito.verify(repository).search(captor.capture(), any(PageRequest.class));

            assertThat(captor.getValue().currencyCode()).isNull();
            assertThat(captor.getValue().isUnfiltered()).isTrue();
        }

        @Test
        @DisplayName("maps an API sort name to the entity property name")
        void mapsSortNameToEntityProperty() throws Exception {
            givenSearchReturns(List.of(US_ACCOUNT), 1);

            mockMvc.perform(get("/api/v1/accounts")
                            .param("sort", "liquidityBuffer")
                            .param("direction", "desc"))
                    .andExpect(status().isOk());

            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            org.mockito.Mockito.verify(repository).search(any(SettlementAccountQuery.class), captor.capture());

            // The API says "liquidityBuffer"; the column is
            // "liquidityBufferAmount". The allow-list enum holds both, so the two
            // names can drift apart without either side knowing.
            assertThat(captor.getValue().sortBy()).isEqualTo("liquidityBufferAmount");
            assertThat(captor.getValue().direction()).isEqualTo(SortDirection.DESC);
        }
    }

    // --- rejections ------------------------------------------------------

    @Nested
    @DisplayName("rejects bad input at the edge")
    class Rejections {

        @Test
        @DisplayName("an oversized page is a 400, and never reaches the repository")
        void rejectsOversizedPage() throws Exception {
            mockMvc.perform(get("/api/v1/accounts").param("size", "10000000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request parameter"))
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("must not exceed 200")));

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a negative page index is a 400")
        void rejectsNegativePage() throws Exception {
            mockMvc.perform(get("/api/v1/accounts").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("zero or greater")));

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an unknown sort field is a 400 naming the valid ones, not a 500 naming our columns")
        void rejectsUnknownSortField() throws Exception {
            // Without the allow-list, this string reaches Spring Data and produces
            // PropertyReferenceException - a 500 whose message enumerates the
            // entity's real property names to whoever sent the request.
            mockMvc.perform(get("/api/v1/accounts").param("sort", "password"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("Unknown sort field")))
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("accountId")));

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an unknown sort direction is a 400")
        void rejectsUnknownDirection() throws Exception {
            mockMvc.perform(get("/api/v1/accounts").param("direction", "sideways"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("asc, desc")));
        }

        @Test
        @DisplayName("a non-numeric page is a 400, handled by Spring's own type-mismatch handler")
        void rejectsNonNumericPage() throws Exception {
            // Nothing of ours runs here - Spring cannot bind "abc" to an int and
            // raises MethodArgumentTypeMismatchException, which
            // ResponseEntityExceptionHandler now maps to 400. Before Layer 3 our
            // catch-all swallowed it and returned 500.
            mockMvc.perform(get("/api/v1/accounts").param("page", "abc"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an unknown jurisdiction is a 400 with an actionable message")
        void rejectsUnknownJurisdiction() throws Exception {
            mockMvc.perform(get("/api/v1/accounts").param("jurisdiction", "ATLANTIS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request parameter"))
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("Valid values")));
        }

        @Test
        @DisplayName("POST to a GET-only collection is 405, not 500")
        void unsupportedMethodIs405() throws Exception {
            // This is the Layer 1/2 defect, fixed. Previously the catch-all
            // @ExceptionHandler(Exception.class) caught Spring's own
            // HttpRequestMethodNotSupportedException and reported a client mistake
            // as a server failure - which means alerts firing for things that are
            // not broken.
            mockMvc.perform(post("/api/v1/accounts"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    // --- single account and writes ---------------------------------------

    @Nested
    @DisplayName("single account")
    class SingleAccount {

        @Test
        @DisplayName("returns the account")
        void returnsAccount() throws Exception {
            given(repository.findByAccountId("ACC-US-0001")).willReturn(Optional.of(US_ACCOUNT));

            mockMvc.perform(get("/api/v1/accounts/ACC-US-0001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bic").value("ATLBUS33XXX"))
                    .andExpect(jsonPath("$.legalEntity").value("ATLAS-BANK-NA"));
        }

        @Test
        @DisplayName("an unknown id yields an RFC 7807 problem detail with 404")
        void unknownIdIsProblemDetail() throws Exception {
            given(repository.findByAccountId("ACC-NOPE")).willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/accounts/ACC-NOPE"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Settlement account not found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.accountId").value("ACC-NOPE"));
        }
    }

    @Nested
    @DisplayName("liquidity buffer updates")
    class BufferUpdates {

        @Test
        @DisplayName("PUT sets a new buffer")
        void setsNewBuffer() throws Exception {
            SettlementAccount updated = US_ACCOUNT.withLiquidityBuffer(Money.of("USD", "31000000.00"));

            given(repository.findByAccountId("ACC-US-0001")).willReturn(Optional.of(US_ACCOUNT));
            given(repository.updateLiquidityBuffer(eq("ACC-US-0001"), any(Money.class))).willReturn(updated);

            mockMvc.perform(put("/api/v1/accounts/ACC-US-0001/liquidity-buffer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"31000000.00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liquidityBuffer").value("31000000.00"));
        }

        @Test
        @DisplayName("PUT to an unknown account is a 404 and never reaches the update")
        void unknownAccountIsNotFound() throws Exception {
            given(repository.findByAccountId("ACC-NOPE")).willReturn(Optional.empty());

            mockMvc.perform(put("/api/v1/accounts/ACC-NOPE/liquidity-buffer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"100.00\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.accountId").value("ACC-NOPE"));
        }

        @Test
        @DisplayName("a non-numeric amount is rejected by Bean Validation before any logic runs")
        void rejectsNonNumericAmount() throws Exception {
            mockMvc.perform(put("/api/v1/accounts/ACC-US-0001/liquidity-buffer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"not-a-number\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.amount").exists());

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("a malformed JSON body is a 400, not a 500")
        void rejectsMalformedJson() throws Exception {
            mockMvc.perform(put("/api/v1/accounts/ACC-US-0001/liquidity-buffer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ this is not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Malformed request body"));
        }
    }

    // --- buffer adjustments (Layer 3 part 2) ------------------------------

    /**
     * The POST adjustment endpoint, at the boundary only.
     *
     * <p>{@link IdempotencyService} is mocked here, which means these tests prove
     * nothing at all about whether idempotency <em>works</em> - a mock cannot enforce
     * "at most once" because there is no database and no transaction. What they do
     * prove is that the controller wires the mechanism up correctly: that it demands
     * a key, that it builds the fingerprint from the fields that matter, that it runs
     * the adjustment inside the idempotent block rather than beside it, and that it
     * reports the outcome honestly in the response header.
     *
     * <p>That division is deliberate and worth being able to explain: a slice test
     * pins the contract, an integration test pins the behaviour. Asserting
     * "applied once" against a mock would be a test that passes whether or not the
     * production code is correct, which is worse than no test.
     */
    @Nested
    @DisplayName("liquidity buffer adjustments")
    class BufferAdjustments {

        private static final String KEY = "6f1c9e2a-0b7d-4f1e-9a3c-2d5e8b1f4a70";
        private static final String PATH = "/api/v1/accounts/ACC-US-0001/liquidity-buffer-adjustments";

        /**
         * Makes the mocked service behave like a first, un-replayed execution: run
         * the supplied action and report {@code replayed = false}.
         *
         * <p>Running the action rather than returning a canned value is the point -
         * it is what lets the tests below observe that the controller passes the real
         * adjustment into the idempotent block, and it is what lets a failure inside
         * the adjustment propagate out to the exception handler the way it would in
         * production.
         */
        private void givenTheActionRuns() {
            given(idempotencyService.execute(
                            anyString(), anyString(), anyString(),
                            eq(SettlementAccountResponse.class), any()))
                    .willAnswer(invocation -> {
                        Supplier<SettlementAccountResponse> action = invocation.getArgument(4);
                        return new IdempotencyService.IdempotentResult<>(action.get(), false);
                    });
        }

        @Test
        @DisplayName("applies the adjustment and reports that it was not a replay")
        void appliesAdjustment() throws Exception {
            SettlementAccount adjusted = US_ACCOUNT.withLiquidityBuffer(Money.of("USD", "30000000.00"));
            given(adjustmentService.adjustBy(eq("ACC-US-0001"), any(BigDecimal.class))).willReturn(adjusted);
            givenTheActionRuns();

            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"5000000.00\",\"reason\":\"CLS window top-up\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotency-Replayed", "false"))
                    .andExpect(jsonPath("$.liquidityBuffer").value("30000000.00"));

            // The delta reaches the service with its scale intact. BigDecimal.equals
            // is scale-sensitive on purpose, so this also pins down that we did not
            // round-trip the amount through a double on the way in.
            verify(adjustmentService).adjustBy("ACC-US-0001", new BigDecimal("5000000.00"));
        }

        @Test
        @DisplayName("fingerprints the account and the amount, and names the operation")
        void buildsTheFingerprint() throws Exception {
            given(adjustmentService.adjustBy(anyString(), any(BigDecimal.class))).willReturn(US_ACCOUNT);
            givenTheActionRuns();

            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"5000000.00\",\"reason\":\"whatever\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<String> operation = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
            verify(idempotencyService).execute(
                    eq(KEY), operation.capture(), fingerprint.capture(),
                    eq(SettlementAccountResponse.class), any());

            // A stable, explicitly built string - NOT the request JSON. Hashing JSON
            // would make {"amount":"5","reason":"a"} and {"reason":"a","amount":"5"}
            // look like different requests, so a client that reordered its fields
            // between a call and its retry would be told 422 for a request that was
            // genuinely identical.
            assertThat(fingerprint.getValue()).isEqualTo("ACC-US-0001|5000000.00");

            // The operation name namespaces the key. Without it, the same UUID used
            // against a different endpoint would collide and replay the wrong answer.
            assertThat(operation.getValue()).isEqualTo("adjust-liquidity-buffer");

            // Note what is NOT in the fingerprint: "reason" is a free-text audit note,
            // so two retries that differ only in wording must still be recognised as
            // the same request. Deciding which fields are semantically part of a
            // request is a judgement call, and getting it wrong in either direction
            // is a real bug.
        }

        @Test
        @DisplayName("a replay returns the stored response and never re-runs the work")
        void replayDoesNotRerunTheWork() throws Exception {
            SettlementAccountResponse stored =
                    SettlementAccountResponse.from(US_ACCOUNT.withLiquidityBuffer(Money.of("USD", "30000000.00")));

            given(idempotencyService.execute(
                            anyString(), anyString(), anyString(),
                            eq(SettlementAccountResponse.class), any()))
                    .willReturn(new IdempotencyService.IdempotentResult<>(stored, true));

            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"5000000.00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotency-Replayed", "true"))
                    .andExpect(jsonPath("$.liquidityBuffer").value("30000000.00"));

            // The header is not decoration. A client debugging a retry loop needs to
            // know its duplicate was recognised; two indistinguishable 200s tell it
            // nothing.
            verifyNoInteractions(adjustmentService);
        }

        @Test
        @DisplayName("reusing a key with a different payload is a 422")
        void keyReuseIs422() throws Exception {
            given(idempotencyService.execute(
                            anyString(), anyString(), anyString(),
                            eq(SettlementAccountResponse.class), any()))
                    .willThrow(new IdempotencyExceptions.KeyReuseException(KEY));

            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"50000000.00\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.title").value("Idempotency key reused"))
                    .andExpect(jsonPath("$.idempotencyKey").value(KEY))
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("Use a new key")));

            verify(adjustmentService, never()).adjustBy(anyString(), any(BigDecimal.class));
        }

        @Test
        @DisplayName("a concurrent request with the same key is a 409 that tells the client to retry")
        void keyInFlightIs409() throws Exception {
            given(idempotencyService.execute(
                            anyString(), anyString(), anyString(),
                            eq(SettlementAccountResponse.class), any()))
                    .willThrow(new IdempotencyExceptions.KeyInFlightException(KEY));

            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"5000000.00\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Request already in progress"))
                    // A machine client told only "409" writes a tight retry loop and
                    // turns a one-second race into a self-inflicted outage.
                    .andExpect(jsonPath("$.retryAfterSeconds").value(1));
        }

        @Test
        @DisplayName("a missing Idempotency-Key header is a 400, not a 500")
        void missingKeyIs400() throws Exception {
            // @RequestHeader is required by default, so Spring raises
            // MissingRequestHeaderException. That becomes a 400 only because
            // GlobalExceptionHandler extends ResponseEntityExceptionHandler - before
            // Layer 3 part 1 the catch-all would have reported this client mistake as
            // a server failure.
            mockMvc.perform(post(PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"5000000.00\"}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(idempotencyService);
        }

        @Test
        @DisplayName("a malformed amount is rejected before the key is ever claimed")
        void malformedAmountNeverClaimsTheKey() throws Exception {
            // Bean Validation runs before the controller body, so the key is not
            // burned and the client can correct its request and reuse it. Validating
            // shape at the edge is what makes that true.
            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"not-a-number\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.amount").exists());

            verifyNoInteractions(idempotencyService);
            verifyNoInteractions(adjustmentService);
        }

        @Test
        @DisplayName("a negative amount is valid - unlike the absolute-set request")
        void negativeAmountIsAccepted() throws Exception {
            SettlementAccount reduced = US_ACCOUNT.withLiquidityBuffer(Money.of("USD", "20000000.00"));
            given(adjustmentService.adjustBy(eq("ACC-US-0001"), any(BigDecimal.class))).willReturn(reduced);
            givenTheActionRuns();

            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"-5000000.00\"}"))
                    .andExpect(status().isOk());

            verify(adjustmentService).adjustBy("ACC-US-0001", new BigDecimal("-5000000.00"));
        }

        @Test
        @DisplayName("an adjustment that would take the buffer below zero is a 400")
        void belowZeroIs400() throws Exception {
            given(adjustmentService.adjustBy(anyString(), any(BigDecimal.class)))
                    .willThrow(new IllegalArgumentException(
                            "Adjustment of USD -99000000.00 would take the buffer below zero"));
            givenTheActionRuns();

            // 400 rather than 500 because the service throws IllegalArgumentException
            // and it is a @Service, not a @Repository - so Spring's persistence
            // exception translation never rewrites it into
            // InvalidDataAccessApiUsageException. That rewrite is exactly the Layer 2
            // bug, and this test is here so the fix cannot quietly regress.
            mockMvc.perform(post(PATH)
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"-99000000.00\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("below zero")));
        }

        @Test
        @DisplayName("adjusting an unknown account is a 404")
        void unknownAccountIs404() throws Exception {
            given(adjustmentService.adjustBy(anyString(), any(BigDecimal.class)))
                    .willThrow(new NoSuchElementException("No settlement account found with id: ACC-NOPE"));
            givenTheActionRuns();

            mockMvc.perform(post("/api/v1/accounts/ACC-NOPE/liquidity-buffer-adjustments")
                            .header(IdempotencyService.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"1000.00\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("every response carries a correlation id, echoing the caller's when supplied")
    void echoesCorrelationId() throws Exception {
        givenSearchReturns(List.of(US_ACCOUNT), 1);

        mockMvc.perform(get("/api/v1/accounts").header(CorrelationIdFilter.HEADER, "trace-abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "trace-abc-123"));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }
}
