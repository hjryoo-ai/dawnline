package com.dawnline.messaging.support;

import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 단위 테스트용 {@link ProcessedEventRepository}.
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING} 과 같은 계약을 흉내 낸다 —
 * 처음이면 {@code true}, 이미 있으면 {@code false}. 예외를 던지지 않는다는 점이 중요하다.
 *
 * <p>{@link #deleteProcessedBefore} 도 SQL 과 같은 계약으로 흉내 낸다: 임계 시각보다 <em>이전</em>
 * (경계값 자체는 제외) 행을 <em>오래된 순으로</em> 최대 {@code limit} 개. 이 두 성질이 곧
 * {@code ProcessedEventCleaner} 의 배치 반복이 진행을 보장하는 근거라 그대로 재현해야 한다.
 */
public final class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final Map<String, Instant> processed = new LinkedHashMap<>();
    private final List<Integer> deleteBatchSizes = new ArrayList<>();

    @Override
    public boolean markProcessed(UUID eventId, String consumer, Instant processedAt) {
        return processed.putIfAbsent(key(eventId, consumer), processedAt) == null;
    }

    @Override
    public boolean isProcessed(UUID eventId, String consumer) {
        return processed.containsKey(key(eventId, consumer));
    }

    @Override
    public int deleteProcessedBefore(Instant processedAtBefore, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
        List<String> doomed = processed.entrySet().stream()
                .filter(entry -> entry.getValue().isBefore(processedAtBefore))
                .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
        doomed.forEach(processed::remove);
        deleteBatchSizes.add(doomed.size());
        return doomed.size();
    }

    /** 기록된 (eventId, consumer) 키 목록. */
    public List<String> keys() {
        return List.copyOf(processed.keySet());
    }

    /** 남아 있는 행 수. */
    public int size() {
        return processed.size();
    }

    /** {@link #deleteProcessedBefore} 호출마다 실제로 지운 행 수. 배치 반복을 검증하는 데 쓴다. */
    public List<Integer> deleteBatchSizes() {
        return List.copyOf(deleteBatchSizes);
    }

    private static String key(UUID eventId, String consumer) {
        return eventId + "|" + consumer;
    }
}
