package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Event;

/**
 * Strategy for delivering an event over one channel. Adding a channel = one implementation
 * of this interface + one {@code channel} row with the same {@link #code()}.
 */
public interface NotificationChannel {

    /**
     * Matches {@code channel.code} in the database (e.g. {@code EMAIL}).
     */
    String code();

    /**
     * Checks that {@code address} is a valid destination for this channel.
     *
     * @throws InvalidAddressException with a human-readable reason if it is not
     */
    void validateAddress(String address);

    /**
     * Delivers {@code event} to {@code address}.
     *
     * @throws NotificationDeliveryException if delivery failed (the caller decides on retry)
     */
    void send(String address, Event event);

    /**
     * How the address is shown in the admin API. Override to mask secrets.
     */
    default String displayAddress(String address) {
        return address;
    }
}
