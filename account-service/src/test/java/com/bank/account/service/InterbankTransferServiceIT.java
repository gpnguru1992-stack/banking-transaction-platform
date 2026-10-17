package com.bank.account.service;

import com.bank.account.api.InterbankTransferDtos.InterbankTransferRequest;
import com.bank.account.client.CustomerClient;
import com.bank.account.client.CustomerEligibility;
import com.bank.account.domain.AccountRepository;
import com.bank.account.domain.InterbankStatus;
import com.bank.account.domain.InterbankTransfer;
import com.bank.account.domain.InterbankTransferRepository;
import com.bank.common.event.ClearingResultEvent;
import com.bank.common.event.PaymentRail;
import com.bank.common.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.bank.account.service.InterbankTransferService.CLEARED_ACCOUNT;
import static com.bank.account.service.InterbankTransferService.SUSPENSE_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Interbank transfer money flow against PostgreSQL: hold in suspense, settle once, or refund. */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/common,classpath:db/seed",
        "banking.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=200ms"
})
@Testcontainers
class InterbankTransferServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("mirror.gcr.io/library/postgres:16-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired InterbankTransferService service;
    @Autowired InterbankTransferRepository transfers;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean CustomerClient customerClient;

    @BeforeEach
    void reset() {
        when(customerClient.eligibility(anyString()))
                .thenAnswer(inv -> new CustomerEligibility(inv.getArgument(0), "ACTIVE", "VERIFIED", true, null));
        jdbc.update("DELETE FROM interbank_transfer");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("UPDATE account SET balance = 100000.00 WHERE account_number = 'ACC1001'");
        jdbc.update("UPDATE account SET balance = 0 WHERE account_number IN (?, ?)", SUSPENSE_ACCOUNT, CLEARED_ACCOUNT);
    }

    private static InterbankTransferRequest request(PaymentRail rail, String amount, String ifsc) {
        return new InterbankTransferRequest("ACC1001", "Meena Iyer", "123456789012", ifsc, rail,
                new BigDecimal(amount), "INR", "Invoice 42");
    }

    private static InterbankTransferRequest imps(String amount) {
        return request(PaymentRail.IMPS, amount, "HDFC0001234");
    }

    private BigDecimal balance(String account) {
        return accounts.findByAccountNumber(account).orElseThrow().getBalance();
    }

    private int outboxCount(String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
    }

    private void apply(UUID transferId, boolean settled) {
        ClearingResultEvent result = settled
                ? new ClearingResultEvent(transferId, "HDFCI000123456789", null, null, Instant.now())
                : new ClearingResultEvent(transferId, null, "BENEFICIARY_ACCOUNT_INVALID", "Account does not exist", Instant.now());
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> service.applyClearingResult(result, settled));
    }

    @Test
    void initiateDebitsCustomerAndHoldsMoneyInSuspense() {
        InterbankTransfer t = service.initiate("CUST1001", "ibt-1", imps("10000")).transfer();

        assertThat(t.getStatus()).isEqualTo(InterbankStatus.PENDING);
        assertThat(balance("ACC1001")).isEqualByComparingTo("90000.00");
        assertThat(balance(SUSPENSE_ACCOUNT)).isEqualByComparingTo("10000.00");
        assertThat(outboxCount("InterbankTransferInitiated")).isEqualTo(1);
    }

    @Test
    void settlementMovesMoneyFromSuspenseToClearedExactlyOnce() {
        UUID id = service.initiate("CUST1001", "ibt-2", imps("10000")).transfer().getId();

        apply(id, true);
        apply(id, true);

        InterbankTransfer t = transfers.findById(id).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(InterbankStatus.SETTLED);
        assertThat(t.getUtr()).isEqualTo("HDFCI000123456789");
        assertThat(balance(SUSPENSE_ACCOUNT)).isEqualByComparingTo("0.00");
        assertThat(balance(CLEARED_ACCOUNT)).isEqualByComparingTo("10000.00");
        assertThat(balance("ACC1001")).isEqualByComparingTo("90000.00");
        assertThat(outboxCount("InterbankTransferSettled")).isEqualTo(1);
    }

    @Test
    void rejectionRefundsTheCustomer() {
        UUID id = service.initiate("CUST1001", "ibt-3", imps("10000")).transfer().getId();

        apply(id, false);
        apply(id, true);

        InterbankTransfer t = transfers.findById(id).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(InterbankStatus.REFUNDED);
        assertThat(t.getFailureCode()).isEqualTo("BENEFICIARY_ACCOUNT_INVALID");
        assertThat(balance("ACC1001")).isEqualByComparingTo("100000.00");
        assertThat(balance(SUSPENSE_ACCOUNT)).isEqualByComparingTo("0.00");
        assertThat(balance(CLEARED_ACCOUNT)).isEqualByComparingTo("0.00");
        assertThat(outboxCount("InterbankTransferRefunded")).isEqualTo(1);
    }

    @Test
    void retryWithSameKeyReplaysWithoutDebitingTwice() {
        UUID first = service.initiate("CUST1001", "ibt-4", imps("10000")).transfer().getId();
        InterbankTransferService.InterbankResult again = service.initiate("CUST1001", "ibt-4", imps("10000"));

        assertThat(again.replayed()).isTrue();
        assertThat(again.transfer().getId()).isEqualTo(first);
        assertThat(balance("ACC1001")).isEqualByComparingTo("90000.00");
        assertThatThrownBy(() -> service.initiate("CUST1001", "ibt-4", imps("5000")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("different request body");
    }

    @Test
    void railRulesAreEnforced() {
        assertThatThrownBy(() -> service.initiate("CUST1001", "ibt-5", request(PaymentRail.RTGS, "50000", "HDFC0001234")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("RTGS requires at least");
        assertThatThrownBy(() -> service.initiate("CUST1001", "ibt-6", request(PaymentRail.IMPS, "600000", "HDFC0001234")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("IMPS allows at most");
        assertThatThrownBy(() -> service.initiate("CUST1001", "ibt-7", request(PaymentRail.NEFT, "100", "BANK0000001")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("customer of this bank");
        assertThat(balance("ACC1001")).isEqualByComparingTo("100000.00");
    }

    @Test
    void insufficientFundsIsRecordedAsFailedAndMovesNoMoney() {
        assertThatThrownBy(() -> service.initiate("CUST1001", "ibt-8", request(PaymentRail.NEFT, "150000", "ICIC0000001")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Insufficient balance");

        assertThat(transfers.findByCustomerIdAndIdempotencyKey("CUST1001", "ibt-8").orElseThrow().getStatus())
                .isEqualTo(InterbankStatus.FAILED);
        assertThat(balance("ACC1001")).isEqualByComparingTo("100000.00");
        assertThat(balance(SUSPENSE_ACCOUNT)).isEqualByComparingTo("0.00");
        assertThat(outboxCount("TransferFailed")).isEqualTo(1);
    }
}
