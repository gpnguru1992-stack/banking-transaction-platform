package com.bank.notification.channel;

import com.bank.notification.domain.Channel;
import com.bank.notification.domain.CustomerContact;
import com.bank.notification.domain.Notification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EmailSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailSender(JavaMailSender mailSender, @Value("${banking.notification.from-address}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public Optional<String> recipient(CustomerContact contact) {
        return contact.isEmailOptIn() && contact.getEmail() != null ? Optional.of(contact.getEmail()) : Optional.empty();
    }

    @Override
    public void send(String recipient, Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject(notification.getSubject());
        message.setText(notification.getBody());
        mailSender.send(message);
    }
}
