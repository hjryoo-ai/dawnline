package com.dawnline.tracking.application.port.out;

import com.dawnline.tracking.domain.ScanType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * tracking 이 내보내는 이벤트 (DESIGN.md §4.1, 불변규칙 1).
 *
 * <p>구현은 {@code outbox_events} 에 행을 넣을 뿐이고 그 INSERT 는 부르는 유스케이스의
 * 트랜잭션에 참여한다 — 스캔이 롤백되면 이벤트도 사라진다. {@code KafkaTemplate} 은 이
 * 서비스의 유스케이스 어디에서도 부르지 않는다.
 */
public interface DeliveryEvents {

    /**
     * {@code delivery.status} 하나 — <strong>stop 단위</strong>다 (§4.1, 키 {@code routeId}).
     *
     * <p>주문마다 하나씩 내보내지 않는 이유는 §6.5 1단계가 같은 지점의 주문을 하나의 stop 으로
     * 합치기 때문이다. 한 번의 방문을 여러 사건으로 쪼개면 소비자가 그것을 다시 합쳐야 한다.
     *
     * <p>{@code DEPARTED_CAMP} 는 <strong>여기로 오지 않는다</strong>
     * ({@link ScanType#isPublished()}). 캠프 출발은 라우트의 사건이라 stop 수만큼 반복해 말하는
     * 꼴이 되고, order-service 의 상태 머신은 {@code DISPATCHED} 로 그 구간을 이미 덮는다.
     *
     * @param routeId       라우트 id. 파티션 키다
     * @param stopSeq       stop 순번
     * @param orderIds      <strong>실제로 상태가 옮겨진</strong> 주문들. {@code STALE}·
     *                      {@code AFTER_CANCEL} 은 들어가지 않는다 — 소비자에게 새 사실이 없다
     * @param type          스캔 종류. 계약의 {@code status} 가 된다
     * @param occurredAt    사건 시각. 기사 단말이 말한 시각이지 우리가 처리한 시각이 아니다 —
     *                      정시율(§8.1)이 이 값을 약속창과 비교한다
     * @param failureReason {@code FAILED} 의 사유. 그 밖에는 {@code null}
     */
    void deliveryStatus(UUID routeId, int stopSeq, List<UUID> orderIds, ScanType type,
            Instant occurredAt, @Nullable String failureReason);
}
