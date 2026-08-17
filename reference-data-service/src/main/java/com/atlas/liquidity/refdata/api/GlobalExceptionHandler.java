package com.atlas.liquidity.refdata.api;

import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place where every exception becomes an HTTP response.
 *
 * <p>Responses use {@link ProblemDetail} - Spring's implementation of RFC 7807
 * "Problem Details for HTTP APIs". Rather than every service inventing its own
 * error JSON, RFC 7807 gives a standard shape ({@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}) that client libraries and
 * API gateways already understand. In a bank with hundreds of internal
 * consumers, a consistent machine-readable error contract is worth a great deal.
 *
 * <p><b>The security point, which matters more than it looks.</b> The catch-all
 * handler logs the stack trace server-side but returns a generic message to the
 * caller. Leaking a stack trace to an API consumer discloses your framework
 * versions, package structure and sometimes SQL - reconnaissance material an
 * attacker is happy to have. Expect this to come up in Layer 8 and in any
 * security-minded interview.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://atlas-liquidity.example.com/problems/";

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        log.info("Account lookup miss: {}", ex.accountId());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Settlement account not found");
        problem.setType(URI.create(PROBLEM_BASE + "account-not-found"));
        problem.setProperty("accountId", ex.accountId());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Rejected a malformed request: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request parameter");
        problem.setType(URI.create(PROBLEM_BASE + "invalid-parameter"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        // Full detail to the log, nothing sensitive to the caller.
        log.error("Unhandled exception serving request", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the X-Correlation-Id header when reporting this.");
        problem.setTitle("Internal server error");
        problem.setType(URI.create(PROBLEM_BASE + "internal-error"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
