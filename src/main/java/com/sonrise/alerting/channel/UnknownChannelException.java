package com.sonrise.alerting.channel;

public class UnknownChannelException extends RuntimeException {

    public UnknownChannelException(String code) {
        super("No notification channel implementation for code '" + code + "'");
    }
}
