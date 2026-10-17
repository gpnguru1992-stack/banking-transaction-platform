package com.bank.payment.clearing;

import com.bank.common.event.ClearingResultEvent;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankTransferEvent;
import com.bank.common.event.PaymentRail;
import com.bank.common.event.Topics;
import com.bank.common.messaging.EventPublisher;
import com.bank.payment.clearing.ClearingClient.ClearingUnavailableException;
import com.bank.payment.clearing.ClearingClient.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClearingDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:07:00Z");

    private final ClearingClient client = mock(ClearingClient.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final ClearingProperties properties = new ClearingProperties(null, null, null, null, 3, null);
    private ClearingDispatcher dispatcher;
    private ClearingInstruction instruction;

    @BeforeEach
    void setUp() {
        dispatcher = new ClearingDispatcher(mock(ClearingInstructionRepository.class), client, publisher, properties,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        InterbankTransferEvent event = new InterbankTransferEvent(UUID.randomUUID(), "CUST1001", "ACC1001", "Meena Iyer",
                "123456789012", "HDFC0001234", PaymentRail.IMPS, new BigDecimal("10000.00"), "INR",
                new BigDecimal("90000.00"), "Invoice", null, null, null, NOW);
        instruction = new ClearingInstruction(event, "corr-1", NOW, NOW);
    }

    @Test
    void acceptedPaymentIsSettledAndPublished() {
        when(client.submit(instruction)).thenReturn(new Outcome("ACCEPTED", "UTR1", null, null, NOW));

        dispatcher.process(instruction);

        assertThat(instruction.getStatus()).isEqualTo(ClearingStatus.SETTLED);
        assertThat(instruction.getUtr()).isEqualTo("UTR1");
        verify(publisher).publish(eq(Topics.PAYMENT_EVENTS), anyString(), eq(EventTypes.CLEARING_SETTLED),
                anyString(), anyString(), argThat(p -> "UTR1".equals(((ClearingResultEvent) p).utr())));
    }

    @Test
    void rejectedPaymentIsPublishedWithReason() {
        when(client.submit(instruction)).thenReturn(
                new Outcome("REJECTED", null, "BENEFICIARY_ACCOUNT_INVALID", "No such account", NOW));

        dispatcher.process(instruction);

        assertThat(instruction.getStatus()).isEqualTo(ClearingStatus.REJECTED);
        verify(publisher).publish(eq(Topics.PAYMENT_EVENTS), anyString(), eq(EventTypes.CLEARING_REJECTED),
                anyString(), anyString(),
                argThat(p -> "BENEFICIARY_ACCOUNT_INVALID".equals(((ClearingResultEvent) p).reasonCode())));
    }

    @Test
    void timeoutLeavesOutcomeUnknownAndSchedulesRetryWithoutPublishing() {
        when(client.submit(instruction)).thenThrow(new ClearingUnavailableException("Read timed out", null));

        dispatcher.process(instruction);

        assertThat(instruction.getStatus()).isEqualTo(ClearingStatus.UNKNOWN);
        assertThat(instruction.getAttempts()).isEqualTo(1);
        assertThat(instruction.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        verify(publisher, never()).publish(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void afterTimeoutTheNetworkIsAskedFirstAndNotResubmitted() {
        when(client.submit(instruction)).thenThrow(new ClearingUnavailableException("Read timed out", null));
        dispatcher.process(instruction);
        when(client.inquire(instruction.getTransferId()))
                .thenReturn(Optional.of(new Outcome("ACCEPTED", "UTR2", null, null, NOW)));

        dispatcher.process(instruction);

        assertThat(instruction.getStatus()).isEqualTo(ClearingStatus.SETTLED);
        verify(client, org.mockito.Mockito.times(1)).submit(instruction);
    }

    @Test
    void afterTimeoutAnInstructionTheNetworkNeverReceivedIsResubmitted() {
        when(client.submit(instruction))
                .thenThrow(new ClearingUnavailableException("Connection refused", null))
                .thenReturn(new Outcome("ACCEPTED", "UTR3", null, null, NOW));
        dispatcher.process(instruction);
        when(client.inquire(instruction.getTransferId())).thenReturn(Optional.empty());

        dispatcher.process(instruction);

        assertThat(instruction.getStatus()).isEqualTo(ClearingStatus.SETTLED);
        assertThat(instruction.getUtr()).isEqualTo("UTR3");
    }

    @Test
    void stillUnknownAfterMaxAttemptsGoesToManualReviewNeverToRefund() {
        when(client.submit(instruction)).thenThrow(new ClearingUnavailableException("down", null));
        when(client.inquire(instruction.getTransferId())).thenThrow(new ClearingUnavailableException("down", null));

        for (int i = 0; i < properties.maxAttempts(); i++) {
            dispatcher.process(instruction);
        }

        assertThat(instruction.getStatus()).isEqualTo(ClearingStatus.MANUAL_REVIEW);
        verify(publisher, never()).publish(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void neftWaitsForTheNextBatchWhileImpsAndRtgsGoNow() {
        Duration halfHour = Duration.ofMinutes(30);
        assertThat(ClearingDispatcher.firstAttemptAt(PaymentRail.NEFT, NOW, halfHour))
                .isEqualTo(Instant.parse("2026-10-15T10:30:00Z"));
        assertThat(ClearingDispatcher.firstAttemptAt(PaymentRail.IMPS, NOW, halfHour)).isEqualTo(NOW);
        assertThat(ClearingDispatcher.firstAttemptAt(PaymentRail.RTGS, NOW, halfHour)).isEqualTo(NOW);
    }
}
