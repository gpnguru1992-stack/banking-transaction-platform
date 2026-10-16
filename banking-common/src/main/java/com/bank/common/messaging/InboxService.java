package com.bank.common.messaging;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

public class InboxService {

    private final ProcessedMessageRepository repository;
    private final Clock clock;

    public InboxService(ProcessedMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records the event as processed by {@code consumer}. Returns {@code false} when it was already
     * processed, in which case the caller must skip its side effects. A concurrent duplicate fails on
     * the primary key at commit and is retried, then skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        ProcessedMessage.Key key = new ProcessedMessage.Key(eventId, consumer);
        if (repository.existsById(key)) {
            return false;
        }
        repository.save(new ProcessedMessage(eventId, consumer, clock.instant()));
        return true;
    }
}
