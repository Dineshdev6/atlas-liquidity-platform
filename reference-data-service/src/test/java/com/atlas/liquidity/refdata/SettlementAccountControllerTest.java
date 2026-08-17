package com.atlas.liquidity.refdata;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test.
 *
 * <p><b>Why {@code @WebMvcTest} and not {@code @SpringBootTest}.</b> A slice
 * test starts only the MVC infrastructure - controllers, converters,
 * {@code @ControllerAdvice}, filters - and leaves out repositories, datasources
 * and messaging. It boots in a fraction of the time, and when it fails you know
 * the failure is in the web layer. A suite made entirely of full-context tests
 * is the single most common cause of a slow build, and a slow build is what
 * kills a team's appetite for testing.
 *
 * <p>{@code @MockitoBean} (Spring Boot 3.4+, replacing the deprecated
 * {@code @MockBean}) puts a Mockito mock into the test's application context in
 * place of the real repository. The controller is therefore tested against a
 * contract we control, not against seed data that might change.
 *
 * <p>{@code @Import(WebConfig.class)} is needed because slice tests do not pick
 * up arbitrary {@code @Configuration} classes - only those relevant to the
 * slice. Without it the correlation-ID filter is absent and the last test here
 * fails. That is a genuinely useful thing to have hit once.
 */
@WebMvcTest(SettlementAccountController.class)
@Import(WebConfig.class)
class SettlementAccountControllerTest {

    private static final SettlementAccount US_ACCOUNT = new SettlementAccount(
            "ACC-US-0001", "8801234567", "ATLAS-BANK-NA", "USD", Jurisdiction.US, "ATLBUS33XXX");

    private static final SettlementAccount EU_ACCOUNT = new SettlementAccount(
            "ACC-EU-0001", "DE89370400440532013000", "ATLAS-BANK-EU", "EUR", Jurisdiction.EU, "ATLBDEFFXXX");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementAccountRepository repository;

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
}
