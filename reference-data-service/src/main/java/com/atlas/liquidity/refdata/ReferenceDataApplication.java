package com.atlas.liquidity.refdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Reference Data Service.
 *
 * <p>{@code @SpringBootApplication} is three annotations in a trench coat:
 * {@code @Configuration}, {@code @EnableAutoConfiguration} and
 * {@code @ComponentScan}. The component scan is rooted at <em>this class's
 * package</em>, which is why the package layout matters: anything outside
 * {@code com.atlas.liquidity.refdata} will not be picked up automatically.
 *
 * <p>That is exactly why {@code liquidity-common} classes such as
 * {@code CorrelationIdFilter} are registered explicitly as beans in
 * {@code WebConfig} rather than annotated with {@code @Component}. A shared
 * library should not assume it will be scanned - it should be assembled by the
 * application that consumes it. Be ready to explain this; "why isn't my bean
 * being found" is one of the most common Spring interview questions.
 */
@SpringBootApplication
public class ReferenceDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferenceDataApplication.class, args);
    }
}
