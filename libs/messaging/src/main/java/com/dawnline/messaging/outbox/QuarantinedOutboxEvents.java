package com.dawnline.messaging.outbox;

import java.util.List;

/**
 * 격리 목록 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」).
 *
 * @param total  격리된 행 전체 수 — {@code dawnline_outbox_failed} 게이지와 같은 값이다. {@code events} 보다 크면
 *               {@code limit} 에 잘렸다
 * @param events 격리 시각 순
 */
public record QuarantinedOutboxEvents(long total, List<QuarantinedOutboxEvent> events) {

    public QuarantinedOutboxEvents {
        events = List.copyOf(events);
    }
}
