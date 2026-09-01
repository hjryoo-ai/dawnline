package com.dawnline.messaging.support;

import com.dawnline.messaging.outbox.RecordPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntPredicate;

/**
 * 보낸 레코드를 기록하고, 원하는 순번에서 실패를 주입할 수 있는 {@link RecordPublisher}.
 *
 * <p>부분 실패에서 "첫 실패 지점까지만" 커밋하는지 확인하려면 특정 순번만 실패시킬 수 있어야 한다.
 */
public final class RecordingRecordPublisher implements RecordPublisher {

    /**
     * 보낸 레코드 한 건.
     *
     * @param topic   토픽
     * @param key     파티션 키
     * @param value   봉투 JSON
     * @param headers 헤더
     */
    public record Sent(String topic, String key, String value, Map<String, String> headers) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final IntPredicate failAt;

    private RecordingRecordPublisher(IntPredicate failAt) {
        this.failAt = failAt;
    }

    /** 항상 성공하는 발행기. */
    public static RecordingRecordPublisher alwaysSucceeding() {
        return new RecordingRecordPublisher(index -> false);
    }

    /**
     * @param index 이 순번(0-based)의 전송만 실패시킨다
     */
    public static RecordingRecordPublisher failingAt(int index) {
        return new RecordingRecordPublisher(i -> i == index);
    }

    @Override
    public CompletableFuture<Void> publish(String topic, String key, String value, Map<String, String> headers) {
        int index = sent.size();
        sent.add(new Sent(topic, key, value, Map.copyOf(headers)));
        if (failAt.test(index)) {
            return CompletableFuture.failedFuture(new IllegalStateException("주입된 전송 실패: index=" + index));
        }
        return CompletableFuture.completedFuture(null);
    }

    /** 이 발행기가 받은 레코드들. 실패한 것도 포함된다(전송 시도 자체는 있었으므로). */
    public List<Sent> sent() {
        return List.copyOf(sent);
    }
}
