package com.sonrise.alerting.channel;

import com.sonrise.alerting.domain.Event;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends events as plain-text email over SMTP. The address is an email address.
 */
@Component
public class EmailChannel implements NotificationChannel {

    public static final String CODE = "EMAIL";

    private final JavaMailSender mailSender;
    private final String from;

    public EmailChannel(JavaMailSender mailSender,
                        @Value("${alerting.channels.email.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void validateAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new InvalidAddressException("Email address is required");
        }
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            // Strict parsing accepts "Name <a@b.com>"; we want exactly one plain address.
            if (!address.equals(parsed.getAddress())) {
                throw new InvalidAddressException("Not a plain email address: " + address);
            }
        } catch (AddressException e) {
            throw new InvalidAddressException("Invalid email address: " + address);
        }
    }

    @Override
    public void send(String address, Event event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(address);
        message.setSubject("[" + event.getSeverity() + "] " + event.getTitle());
        message.setText(body(event));
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // Spring Mail nests the whole exception chain into its message; the admin only needs the cause.
            throw new NotificationDeliveryException(
                    "Email to " + address + " failed: " + NestedExceptionUtils.getMostSpecificCause(e).getMessage(), e);
        }
    }

    private String body(Event event) {
        StringBuilder body = new StringBuilder()
                .append(event.getTitle()).append("\n\n")
                .append("Category: ").append(event.getCategory().getName()).append('\n')
                .append("Severity: ").append(event.getSeverity()).append('\n')
                .append("Occurred at: ").append(event.getOccurredAt()).append('\n');
        if (event.getDescription() != null) {
            body.append('\n').append(event.getDescription()).append('\n');
        }
        if (event.getUrl() != null) {
            body.append('\n').append(event.getUrl()).append('\n');
        }
        return body.toString();
    }
}
