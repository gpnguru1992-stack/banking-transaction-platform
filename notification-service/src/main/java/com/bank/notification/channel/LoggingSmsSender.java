package com.bank.notification.channel;

import com.bank.notification.domain.Channel;
import com.bank.notification.domain.CustomerContact;
import com.bank.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * SMS adapter used outside production. Production deployments set
 * {@code banking.notification.sms.provider} to a real gateway adapter (e.g. AWS SNS, Twilio).
 */
@Component
@ConditionalOnProperty(name = "banking.notification.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public Channel channel() {
        return Channel.SMS;
    }

    @Override
    public Optional<String> recipient(CustomerContact contact) {
        return contact.isSmsOptIn() && contact.getPhone() != null ? Optional.of(contact.getPhone()) : Optional.empty();
    }

    @Override
    public void send(String recipient, Notification notification) {
        log.info("SMS to {}: {}", maskPhone(recipient), notification.getBody());
    }

    static String maskPhone(String phone) {
        return phone.length() <= 4 ? "****" : "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
