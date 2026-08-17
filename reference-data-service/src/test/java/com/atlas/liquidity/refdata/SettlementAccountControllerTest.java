package com.atlas.liquidity.refdata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.common.web.CorrelationIdFilter;
import com.atlas.liquidity.refdata.api.SettlementAccountController;
import com.atlas.liquidity.refdata.config.WebConfig;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test. No database, no Docker, runs in about a second.
 *
 * <p>This is the fast loop, and it stays fast precisely because the repository
 * is mocked. The real persistence behaviour is proved once, slowly, in
 * {@code SettlementAccountPersistenceIT}. Duplicating it here would double the
 * runtime and buy nothing.
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

    // --- reads -----------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/accounts returns all accounts")
    void listsAllAccounts() throws Exception {
        given(repository.findAll()).willReturn(List.of(US_ACCOUNT, EU_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountId").value("ACC-US-0001"))
                .andExpect(jsonPath("$[0].currencyCode").value("USD"));
    }

    @Test
    @DisplayName("serialises the liquidity buffer as a string, not a JSON number")
    void serialisesMoneyAsString() throws Exception {
        given(repository.findAll()).willReturn(List.of(US_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                // jsonPath's isString() is the assertion that matters here. A
                // JSON number would be parsed as a double by every JavaScript
                // client and would silently lose precision on large values.
                .andExpect(jsonPath("$[0].liquidityBuffer").isString())
                .andExpect(jsonPath("$[0].liquidityBuffer").value("25000000.00"));
    }

    @Test
    @DisplayName("exposes the data-residency region derived from jurisdiction")
    void exposesResidencyRegion() throws Exception {
        given(repository.findAll()).willReturn(List.of(EU_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jurisdiction").value("EU"))
                .andExpect(jsonPath("$[0].residencyRegion").value("eu-central"));
    }

    @Test
    @DisplayName("GET /api/v1/accounts?currency=USD delegates to the currency lookup")
    void filtersByCurrency() throws Exception {
        given(repository.findByCurrency("USD")).willReturn(List.of(US_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts").param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currencyCode").value("USD"));
    }

    @Test
    @DisplayName("GET /api/v1/accounts/{id} returns the account")
    void returnsSingleAccount() throws Exception {
        given(repository.findByAccountId("ACC-US-0001")).willReturn(Optional.of(US_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts/ACC-US-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bic").value("ATLBUS33XXX"))
                .andExpect(jsonPath("$.legalEntity").value("ATLAS-BANK-NA"));
    }

    @Test
    @DisplayName("unknown account id yields an RFC 7807 problem detail with 404")
    void unknownAccountYieldsProblemDetail() throws Exception {
        given(repository.findByAccountId("ACC-NOPE")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/ACC-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Settlement account not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.accountId").value("ACC-NOPE"));
    }

    @Test
    @DisplayName("an unknown jurisdiction is a 400 with an actionable message")
    void unknownJurisdictionIsBadRequest() throws Exception {
        given(repository.findAll()).willReturn(List.of(US_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts").param("jurisdiction", "ATLANTIS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request parameter"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Valid values")));
    }

    @Test
    @DisplayName("every response carries a correlation id, echoing the caller's when supplied")
    void echoesCorrelationId() throws Exception {
        given(repository.findAll()).willReturn(List.of(US_ACCOUNT));

        mockMvc.perform(get("/api/v1/accounts").header(CorrelationIdFilter.HEADER, "trace-abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "trace-abc-123"));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }

    // --- writes (Layer 2) ------------------------------------------------

    @Test
    @DisplayName("PUT sets a new liquidity buffer")
    void updatesLiquidityBuffer() throws Exception {
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
    @DisplayName("PUT to an unknown account is a 404, and never reaches the update")
    void updateOnUnknownAccountIsNotFound() throws Exception {
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

        // The repository was never touched - validation ran at the edge.
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a missing amount is rejected")
    void rejectsMissingAmount() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/ACC-US-0001/liquidity-buffer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
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
