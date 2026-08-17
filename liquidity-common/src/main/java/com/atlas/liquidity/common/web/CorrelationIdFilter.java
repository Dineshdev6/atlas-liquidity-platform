package com.atlas.liquidity.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Stamps every request with a correlation ID and puts it in the logging MDC.
 *
 * <p><b>Why this is in Layer 1 and not "later".</b> In a distributed system, a
 * single user action fans out across a gateway, three microservices and a Kafka
 * topic. Without a correlation ID threaded through all of them, a production
 * incident becomes a manual archaeology exercise across five log files. Retro-
 * fitting tracing into an existing estate is painful; starting with it is free.
 *
 * <p>The ID is accepted from the caller if present, so an upstream system's ID
 * survives the hop. That is what makes end-to-end tracing across organisational
 * boundaries possible.
 *
 * <p>The {@code finally} block is not optional. Servlet containers pool their
 * threads, so an un-cleared MDC leaks the previous request's ID into the next
 * one - a bug that is invisible in testing and deeply misleading in production.
 */
public class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        String correlationId = resolve(request);
        MDC.put(MDC_KEY, correlationId);

        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader(HEADER, correlationId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolve(ServletRequest request) {
        if (request instanceof HttpServletRequest httpRequest) {
            String incoming = httpRequest.getHeader(HEADER);
            if (incoming != null && !incoming.isBlank()) {
                return incoming.trim();
            }
        }
        return UUID.randomUUID().toString();
    }
}
