package com.bank.notification.channel;

import com.bank.notification.domain.Channel;
import com.bank.notification.domain.CustomerContact;
import com.bank.notification.domain.Notification;

import java.util.Optional;

public interface NotificationSender {

    Channel channel();

    /** Returns the recipient address for this customer, or empty if they cannot / did not opt in to be reached. */
    Optional<String> recipient(CustomerContact contact);

    void send(String recipient, Notification notification);
}
