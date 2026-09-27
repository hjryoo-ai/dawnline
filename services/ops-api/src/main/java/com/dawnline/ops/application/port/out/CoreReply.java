package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.AuditResult;
import java.time.Instant;
import java.time.LocalTime;
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
                QuarantinedOutbox, RouteDetail, VehicleAdded, Vehicle, VehicleList, FleetFeasibility {
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

    /**
     * dispatch 의 라우트 — 지도가 그리는 것 (§5.5 「조회」). 좌표와 순서의 진실은 dispatch 이고 읽기 모델은 집계라서
     * {@code rm_routes} 에 stop 을 두지 않고 조회를 위임한다.
     *
     * @param routeId   라우트
     * @param planId    계획
     * @param vehicleId 차량
     * @param status    라우트 상태
     * @param revision  지금 revision — 재배정이 올린다
     * @param distanceM 거리
     * @param durationS 소요 시간
     * @param costKrw   비용 (불변규칙 9)
     * @param stops     순서대로
     */
    record RouteDetail(UUID routeId, UUID planId, UUID vehicleId, String status, int revision, int distanceM,
            int durationS, long costKrw, List<RouteStop> stops) implements Applied.Body {
        public RouteDetail {
            stops = List.copyOf(stops);
        }
    }

    /**
     * 라우트의 stop 하나 — 주소가 아니라 좌표다(§10 「읽기 모델에는 주소 전체를 저장하지 않음」).
     *
     * @param seq            순서 (1부터)
     * @param lat            위도
     * @param lng            경도
     * @param plannedArrival 계획 도착
     * @param status         stop 상태 — {@code delivery.status} 를 dispatch 가 반영한 값(ADR-047)
     * @param orderIds       이 stop 의 주문 — 재배정의 대상
     */
    record RouteStop(int seq, double lat, double lng, Instant plannedArrival, String status, List<UUID> orderIds) {
        public RouteStop {
            orderIds = List.copyOf(orderIds);
        }
    }

    /**
     * 차량 등록 — dispatch 가 만든 id (ADR-067 결정 1). 감사 행은 위임 전에 쓰므로 이 id 는 행의 {@code target_id} 가 아니라
     * 응답과 코어 로그({@code auditId})에 있다 — 대상은 캠프다.
     *
     * @param id 새 차량 id
     */
    record VehicleAdded(UUID id) implements Applied.Body {
    }

    /**
     * dispatch 의 차량 한 대 — 비활성화의 답이고, 목록 · 템플릿의 원소다.
     *
     * @param id            차량
     * @param campId        캠프
     * @param code          운영자가 부르는 이름
     * @param type          차종
     * @param maxWeightG    최대 중량(g)
     * @param maxVolumeCm3  최대 부피(㎤)
     * @param cold          냉장
     * @param allowsHazmat  위험물 허용
     * @param fixedCostKrw  고정비 (불변규칙 9)
     * @param costPerKmKrw  km 당 비용
     * @param costPerMinKrw 분당 비용
     * @param shiftStart    근무 시작 (벽시계)
     * @param shiftEnd      근무 종료
     * @param active        가용한가
     * @param source        누가 넣었나 — {@code seed} · {@code operator} · {@code peak-sim}
     */
    record Vehicle(UUID id, UUID campId, String code, String type, int maxWeightG, int maxVolumeCm3, boolean cold,
            boolean allowsHazmat, int fixedCostKrw, int costPerKmKrw, int costPerMinKrw, LocalTime shiftStart,
            LocalTime shiftEnd, boolean active, String source) implements Applied.Body {
    }

    /**
     * 캠프의 차량 — dispatch 의 배열을 이름 있는 칸에 담는다(본문의 최상위를 배열로 두지 않는다 — 칸을 더할 자리가 없다).
     *
     * @param vehicles 코드 순
     */
    record VehicleList(List<Vehicle> vehicles) implements Applied.Body {
        public VehicleList {
            vehicles = List.copyOf(vehicles);
        }
    }

    /**
     * 웨이브의 함대 실현 가능성 — dispatch 의 판정 그대로 (§5.3 「함대」, ADR-067 결정 2).
     *
     * @param waveId           웨이브
     * @param campId           캠프
     * @param assessedAt       잰 시각(dispatch 의 주입 시계)
     * @param candidates       계획 대상 후보 수
     * @param stops            통합 후 stop 수
     * @param fleet            계획이 쓸 수 있는 차량 수
     * @param maxStopsPerRoute stop 축의 상한. {@code null} 이면 재지 않았다
     * @param headroomPercent  여유(%)
     * @param feasible         모든 조합이 여유 안이다
     * @param combinations     조합마다 한 줄, 가장 특정한 조합부터
     */
    record FleetFeasibility(UUID waveId, UUID campId, Instant assessedAt, int candidates, int stops, int fleet,
            @Nullable Integer maxStopsPerRoute, int headroomPercent, boolean feasible, List<FleetLine> combinations)
            implements Applied.Body {
        public FleetFeasibility {
            combinations = List.copyOf(combinations);
        }
    }

    /**
     * 조합 한 줄.
     *
     * @param cold              냉장
     * @param hazmat            위험물
     * @param large             대형
     * @param label             사람이 읽을 이름
     * @param status            {@code FEASIBLE} · {@code SHORTFALL} · {@code NO_TEMPLATE}
     * @param demandStops       수요 stop
     * @param demandWeightG     수요 중량(g)
     * @param demandVolumeCm3   수요 부피(㎤)
     * @param vehicles          이 조합을 모두 갖춘 차량 수
     * @param capacityStops     그 stop 슬롯
     * @param capacityWeightG   그 중량 용량(g)
     * @param capacityVolumeCm3 그 부피 용량(㎤)
     * @param shortfall         더할 대수. {@code NO_TEMPLATE} 이면 {@code null}
     * @param template          더할 차량의 원본. 없으면 {@code null}
     */
    record FleetLine(boolean cold, boolean hazmat, boolean large, String label, String status, long demandStops,
            long demandWeightG, long demandVolumeCm3, int vehicles, long capacityStops, long capacityWeightG,
            long capacityVolumeCm3, @Nullable Integer shortfall, @Nullable Vehicle template) {
    }
}
