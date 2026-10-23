package org.biodatacatalyst.middleware.shared;


public final class ApiException extends RuntimeException {

    /** Stable values for {@code errors[].extensions.code}. See {@code docs/frontend.md}. */
    public enum Code {

        /** The request was wrong: blank or over-long term, or pagination out of bounds. Not retryable. */
        BAD_USER_INPUT,

        /** Monarch did not answer within the configured read timeout. Retryable. */
        UPSTREAM_TIMEOUT,

        /** Monarch could not be reached, or answered with a non-2xx status. Usually retryable. */
        UPSTREAM_UNAVAILABLE,

        /**
         * Monarch answered successfully with a body that does not match its documented contract.
         * Distinct from an empty result on purpose: an empty result is a success with no items,
         * whereas this means the response could not be trusted at all.
         */
        UPSTREAM_INVALID_RESPONSE
    }

    private final Code code;

    public ApiException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
