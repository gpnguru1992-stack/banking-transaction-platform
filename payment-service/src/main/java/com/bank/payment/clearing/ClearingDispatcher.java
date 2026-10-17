package com.bank.payment.clearing;

import com.bank.common.event.ClearingResultEvent;
import com.bank.common.event.EventTypes;
import com.bank.common.event.PaymentRail;
import com.bank.common.event.Topics;
import com.bank.common.messaging.EventPublisher;
import com.bank.common.observability.CorrelationId;
import com.bank.payment.clearing.ClearingClient.ClearingUnavailableException;
import com.bank.payment.clearing.ClearingClient.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Sends due instructions to the clearing network and publishes the final answer on payment-events
 * (outbox, same transaction as the status change). Each instruction is claimed and processed in its own
 * short transaction. After a timeout the next attempt first asks the network for the status, and only
 * resubmits if the network never received it.
 */
@Component
public class ClearingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClearingDispatcher.class);

    private final ClearingInstructionRepository repository;
    private final ClearingClient client;
    private final EventPublisher publisher;
    private final ClearingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ClearingDispatcher(ClearingInstructionRepository repository, ClearingClient client, EventPublisher publisher,
                              ClearingProperties properties, PlatformTransactionManager transactionManager, Clock clock,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.client = client;
        this.publisher = publisher;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${banking.clearing.poll-interval:1000}")
    public void dispatchDue() {
        for (int i = 0; i < properties.batchSize(); i++) {
            Boolean processed = transactionTemplate.execute(status -> repository.claimNextDue(clock.instant())
                    .map(instruction -> {
                        CorrelationId.runWith(instruction.getCorrelationId(), () -> process(instruction));
                        return true;
                    })
                    .orElse(false));
            if (!Boolean.TRUE.equals(processed)) {
                return;
            }
        }
    }

    void process(ClearingInstruction instruction) {
        Instant now = clock.instant();
        Outcome outcome;
        try {
            outcome = instruction.getStatus() == ClearingStatus.UNKNOWN
                    ? client.inquire(instruction.getTransferId()).orElseGet(() -> client.submit(instruction))
                    : client.submit(instruction);
        } catch (ClearingUnavailableException e) {
            instruction.outcomeUnknown(e.getMessage(), properties.maxAttempts(), now);
            if (instruction.getStatus() == ClearingStatus.MANUAL_REVIEW) {
                meterRegistry.counter("banking.clearing.manual_review", "rail", instruction.getRail().name()).increment();
                log.error("Interbank transfer {} still unknown after {} attempts; needs manual reconciliation",
                        instruction.getTransferId(), instruction.getAttempts());
            } else {
                log.warn("No answer from clearing for {} (attempt {}), will ask again at {}",
                        instruction.getTransferId(), instruction.getAttempts(), instruction.getNextAttemptAt());
            }
            return;
        }
        String id = instruction.getTransferId().toString();
        if (outcome.accepted()) {
            instruction.settle(outcome.utr(), now);
            publisher.publish(Topics.PAYMENT_EVENTS, id, EventTypes.CLEARING_SETTLED, "InterbankTransfer", id,
                    new ClearingResultEvent(instruction.getTransferId(), outcome.utr(), null, null, now));
        } else {
            instruction.reject(outcome.reasonCode(), outcome.reason(), now);
            publisher.publish(Topics.PAYMENT_EVENTS, id, EventTypes.CLEARING_REJECTED, "InterbankTransfer", id,
                    new ClearingResultEvent(instruction.getTransferId(), null, outcome.reasonCode(), outcome.reason(), now));
        }
        meterRegistry.counter("banking.clearing.results", "rail", instruction.getRail().name(),
                "status", instruction.getStatus().name()).increment();
        log.info("Clearing result for {}: {} {}", id, instruction.getStatus(),
                outcome.accepted() ? outcome.utr() : outcome.reasonCode());
    }

    /** IMPS and RTGS go now; NEFT waits for the next batch boundary (e.g. every 30 minutes). */
    public static Instant firstAttemptAt(PaymentRail rail, Instant now, Duration neftBatchInterval) {
        if (rail != PaymentRail.NEFT) {
            return now;
        }
        long interval = neftBatchInterval.toSeconds();
        long next = (now.getEpochSecond() / interval + 1) * interval;
        return Instant.ofEpochSecond(next);
    }
}
