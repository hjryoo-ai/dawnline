package com.dawnline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.idempotency.ConsumeOutcome;
import com.dawnline.observability.DawnlineMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 소비자가 내는 라벨 값은 카탈로그(DawnlineMetrics)의 닫힌 라벨 값 목록과 그 값을 만드는 enum 을 대조한다 (ADR-060 결정 2).
 *
 * <p>닫힌 라벨에 목록 밖의 값이 오면 등록 헬퍼가 실패한다 — 운영에서는 그 유스케이스가 예외를 낸다. enum 에 값이 느는
 * 날 그 실패가 운영의 첫 등록이 아니라 여기서 나야 한다.
 */
class MetricLabelValuesTest {

    @Test
    void 소비_outcome_은_멱등_결과_전부와_DLQ_와_재처리_건너뜀이다() {
        List<String> outcomes = new ArrayList<>(Arrays.stream(ConsumeOutcome.values()).map(ConsumeOutcome::tag).toList());
        outcomes.add(MessagingMetrics.OUTCOME_DLQ);
        outcomes.add(MessagingMetrics.OUTCOME_REPLAY_NOT_TARGET);

        assertThat(DawnlineMetrics.EVENT_PROCESSED.label(MessagingMetrics.TAG_OUTCOME).values())
                .containsExactlyInAnyOrderElementsOf(outcomes);
    }
}
