package com.bank.customer.api;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerStatus;
import com.bank.customer.domain.KycStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be a valid phone number") String phone,
            @NotNull @Past LocalDate dateOfBirth,
            @Pattern(regexp = "^[A-Z0-9]{8,20}$", message = "must be 8-20 upper-case letters or digits") String nationalId) {
    }

    public record UpdateKycRequest(@NotNull KycStatus kycStatus, @Size(max = 500) String remarks) {
    }

    public record UpdateStatusRequest(@NotNull CustomerStatus status, @NotBlank @Size(max = 500) String reason) {
    }

    public record UpdateContactRequest(
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be a valid phone number") String phone,
            Boolean emailOptIn,
            Boolean smsOptIn) {
    }

    public record CustomerResponse(
            String customerId,
            String firstName,
            String lastName,
            String email,
            String phone,
            LocalDate dateOfBirth,
            String nationalIdMasked,
            CustomerStatus status,
            KycStatus kycStatus,
            Instant kycVerifiedAt,
            boolean emailOptIn,
            boolean smsOptIn,
            Instant createdAt) {

        public static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getCustomerId(), c.getFirstName(), c.getLastName(), c.getEmail(),
                    c.getPhone(), c.getDateOfBirth(), c.getNationalIdMasked(), c.getStatus(), c.getKycStatus(),
                    c.getKycVerifiedAt(), c.isEmailOptIn(), c.isSmsOptIn(), c.getCreatedAt());
        }

        public boolean canTransact() {
            return status == CustomerStatus.ACTIVE && kycStatus == KycStatus.VERIFIED;
        }
    }

    /** Minimal view used by account-service before moving money. */
    public record CustomerEligibilityResponse(
            String customerId,
            CustomerStatus status,
            KycStatus kycStatus,
            boolean eligible,
            String reason) {

        public static CustomerEligibilityResponse from(CustomerResponse c) {
            String reason = null;
            if (c.status() != CustomerStatus.ACTIVE) {
                reason = "Customer is " + c.status();
            } else if (c.kycStatus() != KycStatus.VERIFIED) {
                reason = "KYC is " + c.kycStatus();
            }
            return new CustomerEligibilityResponse(c.customerId(), c.status(), c.kycStatus(), reason == null, reason);
        }
    }
}
