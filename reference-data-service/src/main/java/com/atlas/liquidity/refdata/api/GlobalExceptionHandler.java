package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.common.money.CurrencyMismatchException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place where every exception becomes an HTTP response, using RFC 7807
 * problem details.
 *
 * <p>Layer 2 adds the failure modes a database brings with it. Each mapping
 * below is a deliberate choice of status code, and each is worth being able to
 * defend - "what status would you return for X" is a standard API interview
 * question and the interesting part is always the reasoning.
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

    /**
     * The domain layer's way of saying "no such thing" - thrown by the
     * repository adapter, which knows nothing about HTTP and should not.
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNoSuchElement(NoSuchElementException ex) {
        log.info("Entity not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource not found");
        problem.setType(URI.create(PROBLEM_BASE + "not-found"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * A currency invariant was violated - a buffer denominated in something
     * other than the account's own currency.
     *
     * <p><b>Why this handler exists at all, which is the interesting part.</b>
     * The adapter originally threw {@code IllegalArgumentException} here, and it
     * never reached the handler below. {@code @Repository} enables Spring's
     * persistence exception translation, and because the JPA specification says
     * {@code EntityManager} throws {@code IllegalArgumentException} for API
     * misuse, the translator rewrites <em>any</em>
     * {@code IllegalArgumentException} leaving a repository into
     * {@code InvalidDataAccessApiUsageException}. So a validation failure was
     * silently arriving here as an untyped exception and coming back as a 500.
     *
     * <p>The fix was to throw a domain exception the framework has no opinion
     * about. The moral: a generic JDK exception thrown from inside a framework's
     * territory is not yours any more.
     */
    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        log.warn("Rejected a currency mismatch: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Currency mismatch");
        problem.setType(URI.create(PROBLEM_BASE + "currency-mismatch"));
        problem.setProperty("expectedCurrency", ex.left());
        problem.setProperty("providedCurrency", ex.right());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Bean Validation failures from {@code @Valid}.
     *
     * <p>Returns a field-by-field map rather than one flat sentence, because a
     * client that submits a form needs to know <em>which</em> field to
     * highlight. RFC 7807 allows arbitrary extension members for exactly this.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        log.warn("Rejected an invalid request body: {}", fieldErrors);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request body failed validation");
        problem.setTitle("Validation failed");
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setProperty("errors", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /** Malformed or missing JSON body - the caller's fault, so 400 not 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Rejected an unreadable request body: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON");
        problem.setTitle("Malformed request body");
        problem.setType(URI.create(PROBLEM_BASE + "malformed-body"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Optimistic lock failure: someone else changed the row between our read
     * and our write.
     *
     * <p><b>409 Conflict, and it matters that it is not 500.</b> Nothing is
     * broken - the system worked exactly as designed and refused to let one
     * update silently destroy another. 409 tells the client this is a
     * retryable, meaningful outcome: re-read the resource and try again. A 500
     * would tell it to page someone.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The account was modified by another request. Re-read it and retry.");
        problem.setTitle("Concurrent modification");
        problem.setType(URI.create(PROBLEM_BASE + "concurrent-modification"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * A database constraint said no - a unique index, a check constraint, a
     * foreign key.
     *
     * <p>The message is deliberately vague to the caller. Constraint names and
     * SQL error text describe your schema, and describing your schema to an
     * anonymous caller is free reconnaissance.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("Database constraint violated", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with existing data");
        problem.setTitle("Data integrity violation");
        problem.setType(URI.create(PROBLEM_BASE + "data-integrity"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Belt and braces for the translation trap described above: anything that
     * still arrives as a translated argument error is the caller's fault, so
     * 400 rather than 500.
     *
     * <p>Note this must be declared as a distinct handler even though
     * {@code InvalidDataAccessApiUsageException} is a {@code RuntimeException} -
     * Spring dispatches on the most specific matching handler, and without this
     * one it would fall through to the catch-all at the bottom.
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ProblemDetail handleInvalidDataAccessUsage(InvalidDataAccessApiUsageException ex) {
        log.warn("Rejected an invalid data-access usage: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMostSpecificCause().getMessage());
        problem.setTitle("Invalid request");
        problem.setType(URI.create(PROBLEM_BASE + "invalid-request"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Covers bad enum values, unparseable amounts ({@code NumberFormatException}
     * is an {@code IllegalArgumentException}), and invariant failures raised
     * outside a repository.
     */
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
