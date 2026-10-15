package com.bank.account.client;

public record CustomerEligibility(String customerId, String status, String kycStatus, boolean eligible, String reason) {
}
