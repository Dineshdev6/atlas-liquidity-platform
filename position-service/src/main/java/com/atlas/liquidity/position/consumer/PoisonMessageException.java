package com.atlas.liquidity.position.consumer;

/**
 * A message this consumer will never be able to process, however many times it
 * is redelivered.
 *
 * <p>The distinction between this and any other failure is the entire point.
 * A database being briefly unreachable is <b>transient</b>: retrying is exactly
 * right, and after a few seconds it will work. A message whose body is not valid
 * JSON is <b>permanent</b>: retrying it a thousand times will fail a thousand
 * times, while every well-formed message queued behind it on that partition waits.
 *
 * <p>That is the poison message problem, and it is why the error handler is
 * configured to send this exception straight to the dead letter topic with no
 * retries at all. Treating every failure the same way - retry everything, or
 * retry nothing - gets one of these two cases badly wrong.
 */
public class PoisonMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
