package com.sonrise.alerting.admin;

/** The request is valid but conflicts with the current state. Mapped to 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
