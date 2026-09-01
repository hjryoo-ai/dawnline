package com.dawnline.messaging.support;

import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 단위 테스트용 {@link ProcessedEventRepository}.
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING} 과 같은 계약을 흉내 낸다 —
 * 처음이면 {@code true}, 이미 있으면 {@code false}. 예외를 던지지 않는다는 점이 중요하다.
 */
public final class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final Set<String> processed = new LinkedHashSet<>();

    @Override
    public boolean markProcessed(UUID eventId, String consumer, Instant processedAt) {
        return processed.add(key(eventId, consumer));
    }

    @Override
    public boolean isProcessed(UUID eventId, String consumer) {
        return processed.contains(key(eventId, consumer));
    }

    /** 기록된 (eventId, consumer) 키 목록. */
    public List<String> keys() {
        return List.copyOf(processed);
    }

    private static String key(UUID eventId, String consumer) {
        return eventId + "|" + consumer;
    }
}
