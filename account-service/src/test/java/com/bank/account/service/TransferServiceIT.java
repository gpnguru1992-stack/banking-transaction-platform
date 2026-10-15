package com.bank.account.service;

import com.bank.account.api.TransferDtos.TransferRequest;
import com.bank.account.client.CustomerClient;
import com.bank.account.client.CustomerEligibility;
import com.bank.account.domain.AccountRepository;
import com.bank.account.domain.LedgerEntryRepository;
import com.bank.account.domain.TransferStatus;
import com.bank.common.messaging.OutboxRepository;
import com.bank.common.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Runs the real transfer path against PostgreSQL: row locks, constraints, ledger, outbox. */
@SpringBootTest(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/common,classpath:db/seed",
        "banking.outbox.enabled=false",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=200ms"
})
@Testcontainers
class TransferServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("mirror.gcr.io/library/postgres:16-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired TransferService transferService;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository ledger;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @MockBean CustomerClient customerClient;

    @BeforeEach
    void reset() {
        when(customerClient.eligibility(anyString()))
                .thenAnswer(inv -> new CustomerEligibility(inv.getArgument(0), "ACTIVE", "VERIFIED", true, null));
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("UPDATE account SET balance = 100000.00 WHERE account_number = 'ACC1001'");
        jdbc.update("UPDATE account SET balance = 25000.00 WHERE account_number = 'ACC2001'");
    }

    private static TransferRequest request(String amount) {
        return new TransferRequest("ACC1001", "ACC2001", new BigDecimal(amount), "INR", "Rent");
    }

    private BigDecimal balance(String account) {
        return accounts.findByAccountNumber(account).orElseThrow().getBalance();
    }

    @Test
    void transferMovesMoneyWritesLedgerAndPublishesEvent() {
        TransferResult result = transferService.transfer("CUST1001", "key-1", request("10000"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(balance("ACC1001")).isEqualByComparingTo("90000.00");
        assertThat(balance("ACC2001")).isEqualByComparingTo("35000.00");
        assertThat(ledger.findAll()).filteredOn(e -> result.transfer().getId().equals(e.getReferenceId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = 'TransferCompleted'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void retryWithSameKeyReturnsOriginalResultWithoutMovingMoneyAgain() {
        TransferResult first = transferService.transfer("CUST1001", "key-2", request("10000"));
        TransferResult retry = transferService.transfer("CUST1001", "key-2", request("10000"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transfer().getId()).isEqualTo(first.transfer().getId());
        assertThat(balance("ACC1001")).isEqualByComparingTo("90000.00");
    }

    @Test
    void reusingKeyWithDifferentBodyIsRejected() {
        transferService.transfer("CUST1001", "key-3", request("10000"));
        assertThatThrownBy(() -> transferService.transfer("CUST1001", "key-3", request("5000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void insufficientFundsIsRecordedAsFailedTransferAndNothingMoves() {
        assertThatThrownBy(() -> transferService.transfer("CUST1001", "key-4", request("150000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(balance("ACC1001")).isEqualByComparingTo("100000.00");
        assertThat(balance("ACC2001")).isEqualByComparingTo("25000.00");
        assertThat(jdbc.queryForObject("SELECT status FROM transfer WHERE idempotency_key = 'key-4'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = 'TransferFailed'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void cannotSendFromSomeoneElsesAccount() {
        assertThatThrownBy(() -> transferService.transfer("CUST1002", "key-5", request("100")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("ACCOUNT_NOT_OWNED");
        assertThat(balance("ACC1001")).isEqualByComparingTo("100000.00");
    }

    @Test
    void ineligibleCustomerCannotTransfer() {
        when(customerClient.eligibility("CUST1001"))
                .thenReturn(new CustomerEligibility("CUST1001", "ACTIVE", "PENDING", false, "KYC not verified"));
        assertThatThrownBy(() -> transferService.transfer("CUST1001", "key-6", request("100")))
                .isInstanceOf(BusinessException.class);
        assertThat(balance("ACC1001")).isEqualByComparingTo("100000.00");
    }

    @Test
    void concurrentTransfersNeverOverdrawTheAccount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(() -> {
                try {
                    transferService.transfer("CUST1001", UUID.randomUUID().toString(), request("10000"));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            });
        }
        long succeeded = 0;
        for (Future<Boolean> f : pool.invokeAll(calls)) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(balance("ACC1001")).isEqualByComparingTo("0.00");
        assertThat(balance("ACC2001")).isEqualByComparingTo("125000.00");
    }
}
