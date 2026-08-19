package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.common.money.CurrencyMismatchException;
import com.atlas.liquidity.refdata.idempotency.IdempotencyExceptions;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place where every exception becomes an HTTP response, using RFC 7807
 * problem details.
 *
 * <p><b>Layer 3 fixed the defect this class shipped with.</b> In Layers 1 and 2 it
 * had a catch-all {@code @ExceptionHandler(Exception.class)} and nothing above it
 * to stop that handler eating Spring's own exceptions. So a request to a path
 * that does not exist - which Spring correctly raises as
 * {@code NoResourceFoundException}, a 404 - was caught by the catch-all and served
 * as a <b>500</b>. Same for an unsupported HTTP method (should be 405) and an
 * unreadable body. Client mistakes were being reported as server failures, which
 * means alerts firing for things that are not broken, and a genuine outage lost in
 * the noise.
 *
 * <p><b>The fix is to extend {@link ResponseEntityExceptionHandler}.</b> That base
 * class declares handlers for every Spring MVC exception and - since Spring
 * Framework 6 - already renders them as RFC 7807 problem details with the correct
 * status. Because Spring dispatches to the <em>most specific</em> matching
 * handler, those now win over our {@code Exception} catch-all, which is reduced to
 * its proper job: genuinely unexpected failures.
 *
 * <p>We override two of the base handlers, not to change their status but to
 * enrich the body - a field-by-field error map for validation failures, and a
 * clearer title for malformed JSON. Everything else we inherit, correctly, for
 * free.
 *
 * <p>Each status code below is a deliberate choice worth defending. "What would
 * you return for X" is a standard API interview question and the reasoning is
 * always the interesting part.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://atlas-liquidity.example.com/problems/";

    // --- overrides of Spring MVC's own handlers ---------------------------

    /**
     * Bean Validation failures from {@code @Valid}.
     *
     * <p>The base class already returns 400. We override only to add a
     * field-by-field map, because a client submitting a form needs to know
     * <em>which</em> field to highlight, not just that something was wrong. RFC
     * 7807 allows arbitrary extension members for exactly this.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

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

        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Malformed or missing JSON body - the caller's fault, so 400 and not 500. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        log.warn("Rejected an unreadable request body: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON");
        problem.setTitle("Malformed request body");
        problem.setType(URI.create(PROBLEM_BASE + "malformed-body"));
        problem.setProperty("timestamp", Instant.now());

        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    // --- our own domain and infrastructure exceptions ---------------------

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
     * The domain layer's way of saying "no such thing" - thrown by the repository
     * adapter, which knows nothing about HTTP and should not.
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
     * A currency invariant was violated - a buffer denominated in something other
     * than the account's own currency.
     *
     * <p>This handler exists because of a bug worth remembering. The adapter
     * originally threw {@code IllegalArgumentException}, and it never arrived as
     * one: {@code @Repository} enables Spring's persistence exception translation,
     * which - because the JPA spec uses {@code IllegalArgumentException} for API
     * misuse - rewrites any such exception leaving a repository into
     * {@code InvalidDataAccessApiUsageException}. So the handler below never
     * matched and a validation failure came back as a 500. Throwing a domain
     * exception the framework has no claim on is the fix.
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
     * An idempotency key was reused for a <em>different</em> request.
     *
     * <p><b>422 Unprocessable Content, not 400.</b> The request is syntactically
     * perfect - well-formed JSON, a valid amount, a valid key. It is
     * <em>semantically</em> impossible, because that key already means something
     * else. Drawing that line is what 422 exists for.
     *
     * <p>And it must be an error rather than a silent replay. If we honoured the
     * key, a client that sent "+5,000,000" under key K and later "+50,000,000"
     * under the same K by mistake would get the first response, see success, and
     * believe fifty million had been applied. The money would be right and the
     * client's picture of reality would be wrong - which is worse, because nothing
     * would ever correct it.
     */
    @ExceptionHandler(IdempotencyExceptions.KeyReuseException.class)
    public ProblemDetail handleIdempotencyKeyReuse(IdempotencyExceptions.KeyReuseException ex) {
        log.warn("Idempotency key reused with a different payload: {}", ex.idempotencyKey());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Idempotency key reused");
        problem.setType(URI.create(PROBLEM_BASE + "idempotency-key-reused"));
        problem.setProperty("idempotencyKey", ex.idempotencyKey());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * A request with this idempotency key is already being processed.
     *
     * <p><b>409 Conflict, and the client should just retry.</b> Two concurrent
     * requests with the same key both try to insert the same primary key; the
     * database lets exactly one through. The loser rolls back - including any work
     * it had already done, which is the point - and lands here. On retry the winner
     * has committed, so the loser finds the completed record and receives the
     * original response. End state: applied once, reported consistently to both.
     *
     * <p>{@code Retry-After} is set because a machine client should be told how long
     * to wait rather than hammering us. A 409 with no guidance invites a tight retry
     * loop, which turns a one-second race into a self-inflicted outage.
     */
    @ExceptionHandler(IdempotencyExceptions.KeyInFlightException.class)
    public ProblemDetail handleIdempotencyKeyInFlight(IdempotencyExceptions.KeyInFlightException ex) {
        log.info("Concurrent request for idempotency key {}", ex.idempotencyKey());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Request already in progress");
        problem.setType(URI.create(PROBLEM_BASE + "idempotency-key-in-flight"));
        problem.setProperty("idempotencyKey", ex.idempotencyKey());
        problem.setProperty("retryAfterSeconds", 1);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Optimistic lock failure: someone else changed the row between our read and
     * our write.
     *
     * <p><b>409 Conflict, and it matters that it is not 500.</b> Nothing is broken
     * - the system worked as designed and refused to let one update silently
     * destroy another. 409 tells the client this is a meaningful, retryable
     * outcome: re-read the resource and try again. A 500 would tell it to page
     * someone.
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
     * <p>The message is deliberately vague to the caller. Constraint names and SQL
     * error text describe your schema, and describing your schema to an anonymous
     * caller is free reconnaissance.
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

    /** Belt and braces for the exception-translation trap described above. */
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
     * Covers our own parse and range failures: an unknown sort field, an unknown
     * jurisdiction, a negative page, an oversized page, an unparseable amount
     * ({@code NumberFormatException} is an {@code IllegalArgumentException}).
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

    /**
     * The genuine last resort.
     *
     * <p>Now that {@link ResponseEntityExceptionHandler} sits above this class,
     * this handler only sees exceptions nobody anticipated - which is exactly what
     * a 500 should mean. Before Layer 3 it was also catching every 404 and 405,
     * and that made the metric useless.
     *
     * <p>Full detail goes to the log; nothing internal goes to the caller. Leaking
     * a stack trace discloses your framework versions, package structure and
     * sometimes SQL.
     */
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
