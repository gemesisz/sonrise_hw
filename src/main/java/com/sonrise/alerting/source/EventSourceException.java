package com.sonrise.alerting.source;

/**
 * A source could not be fetched or its response could not be parsed. Nothing was stored.
 */
public class EventSourceException extends RuntimeException {

    public EventSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
