package com.dawnline.tracking.domain;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 주문 하나의 배송 (DESIGN.md §5.4 {@code shipments}).
 *
 * <p>애그리거트는 <strong>주문 단위</strong>다. stop 은 여러 주문을 묶지만(§6.5 1단계
 * {@code StopMerger}) 성공·실패와 정시 여부는 주문마다 답해야 하는 질문이고, 통합된 stop 의
 * 부분 취소가 실재한다([ADR-026 후속 정정](docs/adr/ADR-026-dispatch-cancellation-window.md)).
 *
 * <p>상태 전이는 전부 이 클래스의 메서드를 지난다(불변규칙 6). 세터는 없다.
 */
public final class Shipment {

    private final UUID orderId;

    private UUID routeId;
    private int stopSeq;
    private ShipmentStatus status;
    private Instant plannedArrival;
    private Instant etaAt;
    private Instant promisedEnd;
    private @Nullable Instant deliveredAt;
    private final long version;

    private Shipment(UUID orderId, UUID routeId, int stopSeq, ShipmentStatus status,
            Instant plannedArrival, Instant etaAt, Instant promisedEnd,
            @Nullable Instant deliveredAt, long version) {

        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.routeId = Objects.requireNonNull(routeId, "routeId");
        this.stopSeq = requireValidSeq(stopSeq);
        this.status = Objects.requireNonNull(status, "status");
        this.plannedArrival = Objects.requireNonNull(plannedArrival, "plannedArrival");
        this.etaAt = Objects.requireNonNull(etaAt, "etaAt");
        this.promisedEnd = Objects.requireNonNull(promisedEnd, "promisedEnd");
        this.deliveredAt = deliveredAt;
        this.version = version;
    }

    /**
     * {@code route.assigned} 를 받아 새로 만든다 (§5.4 — status {@code SCHEDULED},
     * {@code eta_at = planned_arrival}).
     *
     * <p>{@code promisedEnd} 는 이벤트의 {@code promisedWindow.end} 다. 그 필드가 계약에서
     * {@code required} 이므로 여기에 널이 올 경로가 없다(Phase 5-1a 계약,
     * {@code contracts/events/README.md} §5).
     *
     * @param orderId        주문 id
     * @param routeId        라우트 id
     * @param stopSeq        stop 순번 (1부터)
     * @param plannedArrival 계획 도착 시각
     * @param promisedEnd    약속창의 끝. at-risk 판정의 기준이다
     * @return 새 배송
     */
    public static Shipment scheduled(UUID orderId, UUID routeId, int stopSeq,
            Instant plannedArrival, Instant promisedEnd) {
        return new Shipment(orderId, routeId, stopSeq, ShipmentStatus.SCHEDULED,
                plannedArrival, plannedArrival, promisedEnd, null, 0L);
    }

    /**
     * 저장된 행에서 되살린다 (어댑터 전용).
     *
     * @param orderId        주문 id
     * @param routeId        라우트 id
     * @param stopSeq        stop 순번
     * @param status         상태
     * @param plannedArrival 계획 도착 시각
     * @param etaAt          현재 ETA
     * @param promisedEnd    약속창의 끝
     * @param deliveredAt    완료 시각. 미완료면 {@code null}
     * @param version        낙관적 락 버전
     * @return 되살린 배송
     */
    public static Shipment restore(UUID orderId, UUID routeId, int stopSeq, ShipmentStatus status,
            Instant plannedArrival, Instant etaAt, Instant promisedEnd,
            @Nullable Instant deliveredAt, long version) {
        return new Shipment(orderId, routeId, stopSeq, status,
                plannedArrival, etaAt, promisedEnd, deliveredAt, version);
    }

    /**
     * 기사 스캔 하나를 적용한다 (§5.4).
     *
     * <p>세 갈래다. 순서가 규칙이다 — <strong>취소를 먼저 묻는다.</strong> 진행 축으로 먼저
     * 물으면 {@code CANCELLED} 가 축 밖이라 취소 뒤의 완료 스캔이 <em>전이 실패</em>로 터지고,
     * 그러면 기사 단말이 500 을 받는다. 그것은 기사가 고칠 수 있는 문제가 아니다.
     *
     * <ol>
     *   <li>{@code CANCELLED} 면 {@link ScanOutcome#AFTER_CANCEL} — 무시하되 <strong>센다</strong></li>
     *   <li>이미 지나온 지점이면 {@link ScanOutcome#STALE} — 중복이거나 순서 뒤바뀜이다</li>
     *   <li>그 밖에는 전이한다. 건너뜀은 받아들인다 — 도착 스캔을 빼먹고 완료를 찍는 일은 흔하고,
     *       그때 물건은 실제로 전달됐다</li>
     * </ol>
     *
     * @param type 스캔 종류
     * @param at   사건 시각. 기사 단말이 말한 시각이지 우리가 처리한 시각이 아니다
     * @return 적용 결과
     */
    public ScanOutcome recordScan(ScanType type, Instant at) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");

        if (status == ShipmentStatus.CANCELLED) {
            return ScanOutcome.AFTER_CANCEL;
        }
        ShipmentStatus target = type.targetStatus();
        if (status.hasProgressedPast(target)) {
            return ScanOutcome.STALE;
        }
        transitionTo(target);
        if (target == ShipmentStatus.COMPLETED) {
            this.deliveredAt = at;
        }
        return ScanOutcome.APPLIED;
    }

    /**
     * 개정된 {@code route.assigned} 를 반영한다 (§6.8 4단계).
     *
     * <p><strong>종결 상태는 되돌리지 않는다.</strong> §6.8 의 부분 재계획은 완료·진행 중 stop 을
     * 고정하지만, tracking 은 그것을 페이로드가 말해 주기를 기다리지 않고 <em>자기 규칙으로</em>
     * 지킨다. Phase 5-5 이전에는 dispatch 가 배송 진행을 모르므로 이 규칙이 tracking 쪽의 유일한
     * 방어선이다.
     *
     * <p>{@code CANCELLED} 도 여기서는 종결로 본다. 되돌릴 것이 있어서가 아니라 <em>갱신할 것이
     * 없기 때문</em>이다 — 취소된 배송의 계획 도착 시각을 옮기는 일은 아무 질문에도 답하지 않는다.
     *
     * <p>ETA 는 계획값으로 되돌아간다. 개정은 새 계획이고, 그 계획 이전의 편차는 그 계획에 이미
     * 반영돼 있다. 편차 전파는 다음 스캔부터 다시 쌓인다(§5.4 ETA 재계산, Phase 5-1b).
     *
     * @param routeId        새 라우트 id. 재계획이 stop 을 옮겼을 수 있다(§6.8 {@code relocate})
     * @param stopSeq        새 stop 순번
     * @param plannedArrival 새 계획 도착 시각
     * @param promisedEnd    약속창의 끝
     * @return 반영했으면 {@code true}, 종결 상태라 그대로 두었으면 {@code false}
     */
    public boolean applyRevision(UUID routeId, int stopSeq, Instant plannedArrival,
            Instant promisedEnd) {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(plannedArrival, "plannedArrival");
        Objects.requireNonNull(promisedEnd, "promisedEnd");
        requireValidSeq(stopSeq);

        if (status.isTerminal()) {
            return false;
        }
        this.routeId = routeId;
        this.stopSeq = stopSeq;
        this.plannedArrival = plannedArrival;
        this.etaAt = plannedArrival;
        this.promisedEnd = promisedEnd;
        return true;
    }

    /**
     * ETA 를 옮긴다 — 앞선 stop 의 편차 전파 (§5.4 ETA 재계산, Phase 5-1b).
     *
     * <p><strong>얼마나 옮기는지는 이 애그리거트가 정하지 않는다.</strong> 편차는 라우트의
     * 성질이고(어느 stop 에서 얼마가 벌어졌나), 그것이 <em>누구에게</em> 전파되는지는
     * 방문 순서를 아는 쪽만 안다. 여기서 절반만 계산하면 「어디서 움직이는가」의 답이 둘이
     * 된다 — 그래서 받는 것은 결과값 하나다.
     *
     * <p>종결 상태는 옮기지 않는다. 배송이 끝난 stop 의 도착 예정 시각을 미루는 일은 아무
     * 물음에도 답하지 않는다 — {@link #applyRevision} 이 종결을 그대로 두는 것과 같은 이유다.
     *
     * @param eta 새 ETA
     * @return 옮겼으면 {@code true}, 종결 상태라 그대로 두었으면 {@code false}
     */
    public boolean projectEta(Instant eta) {
        Objects.requireNonNull(eta, "eta");
        if (status.isTerminal()) {
            return false;
        }
        if (eta.equals(etaAt)) {
            // 편차가 0 이거나 이미 같은 값이다. 쓰지 않으면 낙관적 락 충돌도 UPDATE 도 없다.
            return false;
        }
        this.etaAt = eta;
        return true;
    }

    /**
     * 약속을 지키지 못할 위험인가 — {@code eta > promised_end − margin} (§5.4).
     *
     * <p>판정이 애그리거트에 있는 이유: 비교하는 두 값이 <strong>둘 다 이 배송의 것</strong>
     * 이다. 여유(margin)만 밖에서 온다 — 그것은 정책이고 §5.4 가 15분으로 정했다.
     *
     * @param margin 약속 끝에서 앞당겨 보는 여유
     * @return 위험하면 {@code true}. 종결 상태는 언제나 {@code false} 다 — 이미 끝났다
     */
    public boolean isAtRisk(Duration margin) {
        Objects.requireNonNull(margin, "margin");
        return !status.isTerminal() && etaAt.isAfter(promisedEnd.minus(margin));
    }

    /**
     * 취소를 반영한다 — {@code route.assigned} 의 {@code cancelledOrderIds} 또는
     * {@code status: CANCELLED} (§6.10, [ADR-026](docs/adr/ADR-026-dispatch-cancellation-window.md)).
     *
     * <p>이미 {@code CANCELLED} 면 아무 일도 하지 않는다 — 개정이 여러 번 와도 같은 취소가 계속
     * 실려 오기 때문이다(취소된 stop 은 페이로드에서 지우지 않는다).
     *
     * <p>{@code ARRIVED} 이후에는 전이가 없어 예외가 된다. 다만 그 경우는 dispatch 가 이미
     * 거부하므로(§6.10 넷째 분기) 여기까지 오면 상류의 결함이고, 조용히 넘기면 그 결함이 보이지
     * 않는다.
     *
     * <p>시각을 받지 않는다 — {@code shipments} 에 취소 시각을 적는 칸이 없고(§5.4 DDL),
     * 쓸 곳 없는 인자는 다음 사람에게 "어딘가에 남는다" 고 말한다.
     *
     * @return 취소했으면 {@code true}, 이미 취소돼 있었거나 종결 상태면 {@code false}
     */
    public boolean cancel() {
        if (status == ShipmentStatus.CANCELLED) {
            return false;
        }
        if (status.isTerminal()) {
            // 배송이 끝난 주문의 취소다. 상태는 그대로 두고 상류가 세게 한다 — 여기서 예외를
            // 던지면 같은 페이로드의 나머지 stop 까지 롤백된다.
            return false;
        }
        transitionTo(ShipmentStatus.CANCELLED);
        return true;
    }

    private void transitionTo(ShipmentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException("Shipment", status, next);
        }
        this.status = next;
        // ETA 는 여기서 건드리지 않는다. 편차 전파는 이 stop 하나가 아니라 뒤따르는 stop 들의
        // 문제이고(§5.4 ETA 재계산), 그것을 애그리거트 안에서 절반만 하면 "어디서 움직이는가" 의
        // 답이 둘이 된다. Phase 5-1b 가 라우트 단위로 옮긴다.
    }

    private static int requireValidSeq(int stopSeq) {
        if (stopSeq < 1) {
            throw new IllegalArgumentException("stopSeq 는 1 이상이어야 합니다: " + stopSeq);
        }
        return stopSeq;
    }

    /** 주문 id. */
    public UUID orderId() {
        return orderId;
    }

    /** 라우트 id. */
    public UUID routeId() {
        return routeId;
    }

    /** stop 순번. */
    public int stopSeq() {
        return stopSeq;
    }

    /** 상태. */
    public ShipmentStatus status() {
        return status;
    }

    /** 계획 도착 시각. */
    public Instant plannedArrival() {
        return plannedArrival;
    }

    /** 현재 ETA. */
    public Instant etaAt() {
        return etaAt;
    }

    /** 약속창의 끝. */
    public Instant promisedEnd() {
        return promisedEnd;
    }

    /** 완료 시각. 미완료면 {@code null}. */
    public @Nullable Instant deliveredAt() {
        return deliveredAt;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
