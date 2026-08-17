package com.atlas.liquidity.refdata.config;

import com.atlas.liquidity.common.web.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Explicit assembly of cross-cutting web concerns pulled in from
 * {@code liquidity-common}.
 *
 * <p>{@code CorrelationIdFilter} lives in a shared library outside this
 * application's component-scan root, so it is registered here by hand. That is
 * the right default for a shared library: the consuming application decides
 * what it wants and in what order, instead of a transitive dependency silently
 * inserting a filter into everyone's request pipeline.
 *
 * <p><b>Order matters.</b> {@code HIGHEST_PRECEDENCE} puts this filter first, so
 * the correlation ID is in the MDC before any other filter - including security
 * filters in Layer 8 - has a chance to log. A correlation ID that arrives after
 * the authentication failure you are trying to trace is not much use.
 *
 * <p>The alternative is to publish a Spring Boot auto-configuration from
 * {@code liquidity-common}. That is the more polished library design, and it is
 * a good thing to mention as the next step - but the explicit version is easier
 * to reason about while the platform is small.
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("correlationIdFilter");
        return registration;
    }
}
