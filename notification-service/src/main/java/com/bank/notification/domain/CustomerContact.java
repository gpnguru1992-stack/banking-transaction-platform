package com.bank.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Local copy of customer contact details, kept up to date from customer-events. */
@Entity
@Table(name = "customer_contact")
public class CustomerContact {

    @Id
    @Column(name = "customer_id", length = 20)
    private String customerId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "email_opt_in", nullable = false)
    private boolean emailOptIn;

    @Column(name = "sms_opt_in", nullable = false)
    private boolean smsOptIn;

    @Column(name = "source_event_at", nullable = false)
    private Instant sourceEventAt;

    protected CustomerContact() {
    }

    public CustomerContact(String customerId) {
        this.customerId = customerId;
    }

    /** Applies an update unless it is older than what we already have (events may be redelivered). */
    public boolean apply(String fullName, String email, String phone, boolean emailOptIn, boolean smsOptIn, Instant at) {
        if (sourceEventAt != null && at.isBefore(sourceEventAt)) {
            return false;
        }
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.emailOptIn = emailOptIn;
        this.smsOptIn = smsOptIn;
        this.sourceEventAt = at;
        return true;
    }

    public String getCustomerId() { return customerId; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public boolean isEmailOptIn() { return emailOptIn; }
    public boolean isSmsOptIn() { return smsOptIn; }
}
