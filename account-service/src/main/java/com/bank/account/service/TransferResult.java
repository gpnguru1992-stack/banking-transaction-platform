package com.bank.account.service;

import com.bank.account.domain.Transfer;

/** @param replayed true when the response is the stored outcome of an earlier identical request */
public record TransferResult(Transfer transfer, boolean replayed) {
}
