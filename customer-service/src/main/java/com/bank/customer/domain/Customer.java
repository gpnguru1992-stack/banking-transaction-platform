package com.bank.customer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true, length = 20)
    private String customerId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "national_id_masked", length = 20)
    private String nationalIdMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    private KycStatus kycStatus;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    @Column(name = "email_opt_in", nullable = false)
    private boolean emailOptIn = true;

    @Column(name = "sms_opt_in", nullable = false)
    private boolean smsOptIn = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Customer() {
    }

    public Customer(String customerId, String firstName, String lastName, String email, String phone,
                    LocalDate dateOfBirth, String nationalIdMasked, Instant now) {
        this.customerId = customerId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.dateOfBirth = dateOfBirth;
        this.nationalIdMasked = nationalIdMasked;
        this.status = CustomerStatus.ACTIVE;
        this.kycStatus = KycStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateKyc(KycStatus newStatus, Instant now) {
        this.kycStatus = newStatus;
        this.kycVerifiedAt = newStatus == KycStatus.VERIFIED ? now : null;
        this.updatedAt = now;
    }

    public void changeStatus(CustomerStatus newStatus, Instant now) {
        if (this.status == CustomerStatus.CLOSED) {
            throw new IllegalStateException("A closed customer cannot be re-activated");
        }
        this.status = newStatus;
        this.updatedAt = now;
    }

    public void updateContact(String email, String phone, Boolean emailOptIn, Boolean smsOptIn, Instant now) {
        if (email != null) {
            this.email = email;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (emailOptIn != null) {
            this.emailOptIn = emailOptIn;
        }
        if (smsOptIn != null) {
            this.smsOptIn = smsOptIn;
        }
        this.updatedAt = now;
    }

    /** A customer may transact only when active and KYC-verified. */
    public boolean canTransact() {
        return status == CustomerStatus.ACTIVE && kycStatus == KycStatus.VERIFIED;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public Long getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getNationalIdMasked() { return nationalIdMasked; }
    public CustomerStatus getStatus() { return status; }
    public KycStatus getKycStatus() { return kycStatus; }
    public Instant getKycVerifiedAt() { return kycVerifiedAt; }
    public boolean isEmailOptIn() { return emailOptIn; }
    public boolean isSmsOptIn() { return smsOptIn; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
