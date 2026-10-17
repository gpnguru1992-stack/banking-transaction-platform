package com.bank.clearing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Behaves like the clearing switch plus the beneficiary bank. Idempotent on {@code reference}: resubmitting
 * the same reference returns the original decision. Test behaviour is chosen by the beneficiary account:
 * <ul>
 *   <li>ends in {@code 0000}: rejected, account does not exist</li>
 *   <li>ends in {@code 9999}: accepted, but the first reply is slower than the bank's timeout</li>
 *   <li>anything else: accepted with a UTR</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/clearing/payments")
public class ClearingController {

    public record PaymentInstruction(String reference, String rail, String remitterAccount, String beneficiaryName,
                                     String beneficiaryAccount, String beneficiaryIfsc, BigDecimal amount, String currency) {
    }

    public record PaymentStatus(String reference, String status, String utr, String reasonCode, String reason,
                                Instant decidedAt) {
    }

    private final Map<String, PaymentStatus> decisions = new ConcurrentHashMap<>();
    private final Duration slowReply;

    public ClearingController(@Value("${clearing.slow-reply:PT6S}") Duration slowReply) {
        this.slowReply = slowReply;
    }

    @PostMapping
    public PaymentStatus submit(@RequestBody PaymentInstruction instruction) throws InterruptedException {
        boolean firstTime = !decisions.containsKey(instruction.reference());
        PaymentStatus status = decisions.computeIfAbsent(instruction.reference(), ref -> decide(instruction));
        if (firstTime && instruction.beneficiaryAccount().endsWith("9999")) {
            Thread.sleep(slowReply.toMillis());
        }
        return status;
    }

    @GetMapping("/{reference}")
    public ResponseEntity<PaymentStatus> inquire(@PathVariable String reference) {
        PaymentStatus status = decisions.get(reference);
        return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(status);
    }

    private static PaymentStatus decide(PaymentInstruction instruction) {
        Instant now = Instant.now();
        if (instruction.beneficiaryAccount().endsWith("0000")) {
            return new PaymentStatus(instruction.reference(), "REJECTED", null, "BENEFICIARY_ACCOUNT_INVALID",
                    "Beneficiary account does not exist at " + instruction.beneficiaryIfsc(), now);
        }
        String utr = instruction.beneficiaryIfsc().substring(0, 4) + instruction.rail().charAt(0)
                + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        return new PaymentStatus(instruction.reference(), "ACCEPTED", utr, null, null, now);
    }
}
