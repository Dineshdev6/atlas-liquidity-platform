package com.atlas.liquidity.refdata.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published API description.
 *
 * <p><b>Why generate the specification from the code rather than write it.</b> A
 * hand-written OpenAPI file is correct on the day it is written and wrong within a
 * fortnight, because nothing makes it wrong loudly - it just quietly drifts from the
 * controllers while everyone keeps trusting it. springdoc reads the actual mappings,
 * the actual DTOs and the actual Bean Validation constraints at startup, so the
 * document cannot describe an endpoint that does not exist.
 *
 * <p>The trade-off is real and worth naming: code-first means the specification
 * follows the implementation, so you cannot agree a contract with a consumer team
 * before building it. Design-first (write the spec, generate the interfaces) is
 * better when several teams must integrate against something that does not exist
 * yet, which is common in a bank. We are code-first because there is one developer
 * and no consumer waiting - and that is the honest reason, not a claim that one
 * approach is superior.
 *
 * <p><b>Where to find it:</b>
 * <ul>
 *   <li>{@code /swagger-ui.html} - the interactive console. A consumer can read
 *       every endpoint, see the schemas, and fire real requests without writing a
 *       line of code or asking you a question.</li>
 *   <li>{@code /v3/api-docs} - the machine-readable JSON, which is the part that
 *       actually matters: client SDKs, contract tests, and API gateway
 *       configuration can all be generated from it.</li>
 * </ul>
 *
 * <p><b>An operational note for later.</b> Swagger UI is a convenience for
 * developers, not something to expose on a public edge in production - it
 * enumerates your entire attack surface in a friendly, clickable form. Layer 8 puts
 * it behind authentication or disables it outside non-production profiles. Worth
 * mentioning unprompted if OpenAPI comes up in an interview.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI atlasLiquidityOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Atlas Liquidity Platform - Reference Data API")
                        .version("v1")
                        .description("""
                                Settlement account reference data for the cash and intraday liquidity
                                management platform.

                                **Paging.** Collection endpoints return a `content` array plus a nested
                                `page` object. Page size is capped server-side at 200.

                                **Errors.** Every failure is an RFC 7807 problem detail
                                (`application/problem+json`) with a stable `type` URI.

                                **Idempotency.** Non-idempotent operations require an `Idempotency-Key`
                                header. Repeating a request with the same key returns the original
                                response and performs no further work. Reusing a key with a different
                                payload is rejected with 422.

                                **Money.** All monetary amounts cross the wire as strings, never JSON
                                numbers, because a JSON number is a double to most clients and loses
                                precision on large values.

                                **Tracing.** Send `X-Correlation-Id` and it is echoed back and stamped
                                on every log line for the request. Omit it and one is generated.
                                """)
                        .contact(new Contact().name("Atlas Liquidity Engineering"))
                        .license(new License().name("Internal use only")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development")));
    }
}
