package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.AuditResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 코어가 커맨드에 한 답 — 감사 행의 결과가 여기서 나온다 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>갈래는 「적용됐는가를 아는가」로 나뉜다. {@link Unreachable} 만이 「닿지 않았다」를 확실히 말하고,
 * 응답을 받지 못한 나머지는 전부 {@link Unknown} 이다.
 */
public sealed interface CoreReply {

    /** @return 이 답이 감사 행에 남길 결과 */
    AuditResult result();

    /**
     * 2xx — 적용됐다.
     *
     * @param body 코어의 성공 본문을 ops 가 돌려줄 모양으로 옮긴 것
     */
    record Applied(Applied.Body body) implements CoreReply {
        public Applied {
            Objects.requireNonNull(body, "body");
        }

        @Override
        public AuditResult result() {
            return AuditResult.SUCCEEDED;
        }

        /** 코어의 성공 본문. 주소 같은 개인정보는 옮기지 않는다(§10). */
        public sealed interface Body permits PlanRun, StopReassigned, OrderCancelled, WaveClosed, OutboxRequeued,
                QuarantinedOutbox {
        }
    }

    /**
     * 4xx — 코어가 거절했다. 적용되지 않았다.
     *
     * @param status      코어의 상태 코드
     * @param problem     코어의 Problem Details 본문, 바이트 그대로(UTF-8). 프록시로서 옮긴다 — 운영자는
     *                    코어가 말한 것을 그대로 본다(DESIGN.md §5.5)
     * @param contentType 코어가 준 미디어 타입, 없으면 {@code null}
     */
    record Rejected(int status, String problem, @Nullable String contentType) implements CoreReply {
        public Rejected {
            Objects.requireNonNull(problem, "problem");
        }

        @Override
        public AuditResult result() {
            return AuditResult.REJECTED;
        }
    }

    /**
     * 연결이 맺어지지 않았다 — 요청이 코어에 닿지 않았다.
     *
     * @param detail 원인(예외 메시지). 사람이 읽는다
     */
    record Unreachable(String detail) implements CoreReply {
        @Override
        public AuditResult result() {
            return AuditResult.FAILED;
        }
    }

    /**
     * 적용됐는지 모른다.
     *
     * @param timedOut   응답을 기다리다 시간이 다 됐다
     * @param coreStatus 코어가 5xx 로 답했으면 그 코드, 아니면 {@code null}
     * @param detail     원인. 사람이 읽는다
     */
    record Unknown(boolean timedOut, @Nullable Integer coreStatus, String detail) implements CoreReply {
        @Override
        public AuditResult result() {
            return AuditResult.UNKNOWN;
        }
    }

    /**
     * dispatch 의 재계획 결과.
     *
     * @param waveId  웨이브
     * @param outcome 코어가 말한 결과
     */
    record PlanRun(UUID waveId, String outcome) implements Applied.Body {
    }

    /**
     * dispatch 의 재배정 결과 — 두 라우트의 새 revision.
     *
     * @param orderId      옮긴 주문
     * @param fromRouteId  옮긴 쪽
     * @param fromRevision 옮긴 쪽의 새 revision
     * @param toRouteId    받은 쪽
     * @param toRevision   받은 쪽의 새 revision
     */
    record StopReassigned(UUID orderId, UUID fromRouteId, int fromRevision, UUID toRouteId, int toRevision)
            implements Applied.Body {
    }

    /**
     * order 의 취소 결과. 코어의 {@code OrderView} 는 주소 전체를 싣지만 여기로 옮기지 않는다.
     *
     * @param orderId 주문
     * @param status  취소 뒤 상태
     */
    record OrderCancelled(UUID orderId, String status) implements Applied.Body {
    }

    /**
     * fulfillment 의 조기 마감 결과 (ADR-054).
     *
     * @param waveId     웨이브
     * @param status     마감 뒤 상태
     * @param closeCause 마감 원인 — 이 경로로 닫혔으면 {@code MANUAL}
     * @param closedAt   닫힌 시각
     */
    record WaveClosed(UUID waveId, String status, @Nullable String closeCause, @Nullable Instant closedAt)
            implements Applied.Body {
    }

    /**
     * 격리를 풀었다 (§4.6). 릴레이가 다음 폴링에 집는다.
     *
     * @param id            행 id
     * @param aggregateType 애그리거트 종류
     * @param aggregateId   애그리거트 id
     * @param eventType     이벤트 타입
     * @param topic         토픽
     */
    record OutboxRequeued(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic)
            implements Applied.Body {
    }

    /**
     * 한 코어의 격리 목록 (§4.6) — 조회라 감사 행이 없다. {@code payload}·{@code headers} 는 코어가 싣지 않는다.
     *
     * @param total  격리된 행의 전체 수. {@code events} 보다 크면 {@code limit} 에 잘렸다
     * @param events 격리 시각 순
     */
    record QuarantinedOutbox(long total, List<QuarantinedEvent> events) implements Applied.Body {
        public QuarantinedOutbox {
            events = List.copyOf(events);
        }
    }

    /**
     * 격리된 행 하나.
     *
     * @param id              행 id
     * @param aggregateType   애그리거트 종류
     * @param aggregateId     애그리거트 id
     * @param eventType       이벤트 타입
     * @param topic           토픽
     * @param createdAt       만들어진 시각
     * @param failedAt        격리된 시각
     * @param publishAttempts 격리되기까지의 시도 수
     */
    record QuarantinedEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic,
            Instant createdAt, Instant failedAt, int publishAttempts) {
    }
}
