package com.dawnline.ops.support;

import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** {@code processed_events} 의 메모리 구현 (불변규칙 2). */
public final class InMemoryProcessedEvents implements ProcessedEventRepository {

    private final Set<String> processed = new HashSet<>();

    @Override
    public boolean markProcessed(UUID eventId, String consumer, Instant processedAt) {
        return processed.add(eventId + "/" + consumer);
    }

    @Override
    public boolean isProcessed(UUID eventId, String consumer) {
        return processed.contains(eventId + "/" + consumer);
    }

    @Override
    public int deleteProcessedBefore(Instant processedAtBefore, int limit) {
        return 0;
    }
}
