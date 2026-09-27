package com.sonrise.alerting.admin;

/** The request refers to something that doesn't exist or isn't usable. Mapped to 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
