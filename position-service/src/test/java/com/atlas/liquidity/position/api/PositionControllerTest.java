package com.atlas.liquidity.position.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.liquidity.position.projection.AccountPositionEntity;
import com.atlas.liquidity.position.projection.AccountPositionJpaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The read API over the projection. Web layer only - no database, no broker.
 */
@WebMvcTest(PositionController.class)
class PositionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountPositionJpaRepository positions;

    private static AccountPositionEntity position(String accountId, String buffer) {
        return new AccountPositionEntity(
                accountId, "GBP", new BigDecimal(buffer), "evt-1", Instant.parse("2026-08-22T03:24:20Z"));
    }

    @Test
    @DisplayName("returns one position")
    void returnsPosition() throws Exception {
        given(positions.findById("ACC-GB-0001")).willReturn(Optional.of(position("ACC-GB-0001", "20000000.00")));

        mockMvc.perform(get("/api/v1/positions/ACC-GB-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ACC-GB-0001"))
                // A string, not a JSON number. Same precision argument as
                // reference-data-service: a JSON number is a double to most
                // clients, and money in a double is money you will lose.
                .andExpect(jsonPath("$.currentBuffer").isString())
                .andExpect(jsonPath("$.currentBuffer").value("20000000.00"))
                .andExpect(jsonPath("$.appliedCount").value(1));
    }

    @Test
    @DisplayName("an account with no events yet is a 404, not an empty position")
    void unknownAccountIsNotFound() throws Exception {
        given(positions.findById("ACC-NOPE")).willReturn(Optional.empty());

        // "We have never received an event for this account" and "this account's
        // buffer is zero" are different facts, and returning zero for the first
        // one would be a lie a downstream system would act on.
        mockMvc.perform(get("/api/v1/positions/ACC-NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("lists positions in account order")
    void listsPositions() throws Exception {
        given(positions.findAllByOrderByAccountIdAsc())
                .willReturn(List.of(position("ACC-GB-0001", "20000000.00"), position("ACC-US-0001", "25000000.00")));

        mockMvc.perform(get("/api/v1/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountId").value("ACC-GB-0001"));
    }
}
