package com.dawnline.tracking.application.port.in;

import com.dawnline.common.error.ValidationException;
import com.dawnline.tracking.domain.ScanOutcome;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.ShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 기사 스캔 하나를 적용한다 (DESIGN.md §5.4 —
 * {@code POST /api/v1/routes/{id}/stops/{seq}/events}).
 *
 * <h2>멱등은 상태 머신이 만든다</h2>
 * §8.5 의 멱등 키는 「{@code (routeId, seq, type)} + 상태 머신」이다. 별도의 중복 표를 두지
 * 않는 이유는 그 표가 답할 수 있는 것을 상태가 이미 알고 있기 때문이다 — 같은 스캔이 다시 오면
 * 이미 지나온 지점이라 {@link ScanOutcome#STALE} 이 되고, 그것은 「중복이었다」와 「순서가
 * 뒤바뀌어 늦게 왔다」를 <em>같은 방식으로</em> 흡수한다
 * ([ADR-017](docs/adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md) 의 축 규칙).
 * 그래서 단말이 타임아웃 뒤 같은 요청을 다시 보내도 안전하다.
 */
public interface RecordScanUseCase {

    /**
     * 스캔을 적용한다.
     *
     * @param command 스캔
     * @return stop 에 묶인 주문마다의 결과
     * @throws com.dawnline.common.error.NotFoundException 그 라우트·순번에 배송이 없을 때
     */
    ScanResult record(ScanCommand command);

    /**
     * 기사 단말이 보낸 스캔 하나.
     *
     * @param routeId       라우트 id
     * @param stopSeq       stop 순번 (1부터)
     * @param type          스캔 종류
     * @param occurredAt    사건 시각. <strong>단말이 말한 시각</strong>이다 — 우리가 처리한 시각을
     *                      쓰면 정시율(§8.1)이 처리 지연만큼 어긋난다
     * @param lat           위도. 단말이 보내지 않았으면 {@code null}
     * @param lng           경도
     * @param failureReason {@code FAILED} 의 사유. 다른 종류에는 없다
     */
    record ScanCommand(UUID routeId, int stopSeq, ScanType type, Instant occurredAt,
            @Nullable Double lat, @Nullable Double lng, @Nullable String failureReason) {

        public ScanCommand {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (stopSeq < 1) {
                // 도메인 예외를 쓴다 — 이 값은 경로 변수라 클라이언트가 만든 것이고,
                // IllegalArgumentException 이면 500 으로 나간다 (RFC 9457 매핑은 DomainException 만 본다).
                throw ValidationException.field("stopSeq", stopSeq, "1 이상이어야 합니다");
            }
        }
    }

    /**
     * 스캔 결과 — stop 에 묶인 주문마다 한 줄이다.
     *
     * <p>한 스캔이 주문마다 다른 답을 낼 수 있다: 통합된 stop 에서 하나만 취소된 경우
     * ([ADR-026](docs/adr/ADR-026-dispatch-cancellation-window.md) 후속 정정) 살아 있는 주문은
     * {@link ScanOutcome#APPLIED}, 취소된 주문은 {@link ScanOutcome#AFTER_CANCEL} 이다.
     * 합쳐서 하나로 답하면 기사 단말이 「무엇이 안 됐는가」를 알 수 없다.
     *
     * @param routeId    라우트 id
     * @param stopSeq    stop 순번
     * @param type       스캔 종류
     * @param occurredAt 사건 시각
     * @param orders     주문마다의 결과
     */
    record ScanResult(UUID routeId, int stopSeq, ScanType type, Instant occurredAt,
            List<OrderScan> orders) {

        public ScanResult {
            orders = List.copyOf(orders);
        }

        /**
         * @param outcome 셀 결과
         * @return 그 결과가 난 주문 수
         */
        public int count(ScanOutcome outcome) {
            return (int) orders.stream().filter(order -> order.outcome() == outcome).count();
        }
    }

    /**
     * 주문 하나에 대한 결과.
     *
     * @param orderId 주문 id
     * @param outcome 적용 결과
     * @param status  적용 후 상태. {@code APPLIED} 가 아니면 <em>바뀌지 않은</em> 현재 상태다
     */
    record OrderScan(UUID orderId, ScanOutcome outcome, ShipmentStatus status) {
    }
}
