package com.atlas.liquidity.refdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import com.atlas.liquidity.refdata.config.WebConfig;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import java.util.List;
import java.util.Optional;
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
 * <p>The repository is mocked, so these tests are about the <em>boundary</em>:
 * what the controller accepts, what it rejects, what it passes down, and what
 * shape it returns. Whether the query actually works against Postgres is proved
 * once, slowly, in {@code SettlementAccountPersistenceIT}.
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
