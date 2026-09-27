package com.sonrise.alerting.source;

/**
 * A single candidate cannot be stored; the rest of the run continues.
 */
public class RejectedCandidateException extends RuntimeException {

    public RejectedCandidateException(String message) {
        super(message);
    }
}
