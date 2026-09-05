package com.dawnline.fulfillment.application.port.in;

import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code order.placed} 를 받아 FC·캠프·권역·웨이브를 정한다 (§5.2).
 *
 * <p>결과는 어느 쪽이든 <strong>{@code fulfillment.planned} 한 건</strong>이다 — 성공은
 * {@code outcome=PLANNED}, 실패는 {@code outcome=UNSERVICEABLE} + 사유(§4.3). 배차하지 못한 것도
 * 하류가 알아야 하는 사실이라 조용히 끝내지 않는다.
 */
public interface PlanOrderUseCase {

    /**
     * 계획한다.
     *
     * @param snapshot   {@code order.placed} 스냅샷
     * @param placedEventId 그 이벤트의 봉투 eventId. 판정의 출처로 행에 남는다
     */
    PlanOutcome plan(PlacedOrderSnapshot snapshot, UUID placedEventId);

    /**
     * 판정 결과 — 리스너가 메트릭·로그로 번역할 만큼만 담는다.
     *
     * @param kind      무엇이 일어났는가
     * @param waveId    편입된 웨이브 ({@code PLANNED} 일 때만)
     * @param reason    배차 불가 사유 ({@code UNSERVICEABLE} 일 때만)
     * @param campId    캠프. 권역을 못 찾았으면 비어 있다
     * @param revised   약속이 개정됐는가 (ADR-020 결정 3)
     */
    record PlanOutcome(
            Kind kind,
            Optional<UUID> waveId,
            Optional<UnserviceableReason> reason,
            Optional<UUID> campId,
            boolean revised) {

        /** 계획됐다. */
        public static PlanOutcome planned(UUID waveId, UUID campId, boolean revised) {
            return new PlanOutcome(Kind.PLANNED, Optional.of(waveId), Optional.empty(),
                    Optional.of(campId), revised);
        }

        /** 배차할 수 없다. */
        public static PlanOutcome unserviceable(UnserviceableReason reason, UUID campId) {
            return new PlanOutcome(Kind.UNSERVICEABLE, Optional.empty(), Optional.of(reason),
                    Optional.ofNullable(campId), false);
        }

        /**
         * 이미 판정된 주문이라 아무것도 하지 않았다.
         *
         * <p>취소 선착이 대표적이다(ADR-022). 중복 이벤트는 {@code processed_events} 가 앞에서
         * 거르므로 여기까지 오는 것은 <em>다른 eventId 로 같은 주문이 다시 온 경우</em>다.
         */
        public static PlanOutcome ignored(UnserviceableReason unused) {
            return new PlanOutcome(Kind.IGNORED, Optional.empty(), Optional.empty(),
                    Optional.empty(), false);
        }

        /** 결과 종류. */
        public enum Kind {
            /** 웨이브에 편입됐다. */
            PLANNED,
            /** 배차할 수 없다. 사유와 함께 하류로 나간다. */
            UNSERVICEABLE,
            /** 이미 판정된 주문이라 무시했다. */
            IGNORED
        }
    }
}
