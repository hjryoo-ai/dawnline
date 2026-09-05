# Dawnline — 당일·새벽 배송 오더 오케스트레이션 & 디스패치 플랫폼 설계서

| 항목 | 내용 |
|---|---|
| 문서 버전 | v1.0 (2026-08-29) |
| 상태 | 구현 착수 가능 — Claude Code 작업의 기준 문서 |
| 대상 공고 | Coupang Global Operations Tech (GOT) — Staff Backend Engineer |
| 함께 읽을 문서 | `CLAUDE.md`(저장소 규칙), `IMPLEMENTATION_PLAN.md`(Phase별 작업 지시), `docs/adr/*` |

---

## 0. 이 문서의 사용법

- 이 문서는 **진실의 원천(source of truth)** 이다. 코드와 문서가 충돌하면 문서가 우선하며, 설계를 바꿔야 하면 문서를 먼저 고치고 ADR을 남긴 뒤 코드를 수정한다.
- Claude Code는 `IMPLEMENTATION_PLAN.md`의 Phase 순서대로 구현한다. 각 Phase의 완료 기준(DoD)을 충족하기 전에는 다음 Phase로 넘어가지 않는다.
- `[결정 필요]` 표시가 붙은 항목은 구현 전에 사용자 확인을 받는다. 그 외 항목은 확정된 결정이다.
- 수치(컷오프 시각, 용량, 페널티 단가 등)는 **예시 기본값**이며 모두 설정/시드 데이터로 바꿀 수 있어야 한다.

---

## 1. 배경과 목표

### 1.1 왜 이 프로젝트인가

공고의 핵심 문장은 두 가지다.

1. "룰 기반 최소 비용으로 빠르게 고객의 주문을 배송할 수 있는 방법을 찾기 위한 최적 알고리즘 도입"
2. "성수기에도 초고속 배송이 정시에 이루어지도록 하는 고가용성 MSA"

따라서 포트폴리오는 **"주문이 들어와서 기사 경로에 배정되기까지"** 를 실제 물류 도메인 용어(FC·캠프·권역·웨이브·라우트)로 모델링하고, 그 안에서 **룰 엔진 + 비용 기반 경로 최적화**를 핵심으로 삼되, 이벤트 드리븐 MSA·피크 대응·운영 도구까지 한 덩어리로 보여주는 하나의 시스템이어야 한다.

### 1.2 공고 요구사항 → 설계 매핑

| 공고 요구 | 이 프로젝트에서 증명하는 방식 | 문서 위치 |
|---|---|---|
| 시스템 아키텍처 설계·안정적 운영 | 4개 코어 서비스 + 운영 API의 이벤트 드리븐 MSA, 장애 모드 표, 런북, SLO | §3, §8, §9 |
| Java 기반 Front/Back office 웹 서비스 | 고객향 주문 API(front) + 운영자 콘솔 API·UI(back office) | §5.1, §5.5 |
| 여러 도메인 간 dependency 도출·시스템 연동 | 주문→풀필먼트→디스패치→트래킹 의존성 그래프, 이벤트 계약(contracts/), 소비자 호환성 테스트 | §3.3, §4 |
| 새로운 기술 도입 검토 | 자체 휴리스틱 vs Timefold vs OR-Tools 비교 ADR, 벤치마크 하네스 | §6.6, ADR-004 |
| **룰 기반 최소 비용·최속 배송 최적 알고리즘** | 하드/소프트 룰 엔진 + 비용 모델 + 클러스터링→할당→시퀀싱→개선 파이프라인, 설명 가능성(explanation) | §6 |
| Java + Spring, 관계형 DB, 객체지향 설계 | Java 25 / Spring Boot 4.1, PostgreSQL 18, 헥사고날 아키텍처 + DDD 애그리거트 | §3.4, §5, §7, §11 |
| MSA, 클라우드 환경 | DB-per-service, 컨테이너화, 헬스/레디니스 프로브, k8s 매니페스트(선택) | §3, §14 |
| 대용량 비동기 Event Driven Architecture | Kafka 4.x + Transactional Outbox + 멱등 소비자 + DLQ, 파티션 키 설계 | §4 |
| 코드 품질 | ArchUnit 경계 테스트, Testcontainers 통합 테스트, 커버리지 게이트, PR 템플릿 | §13 |
| JPA/Hibernate ORM + 도메인 모델링 | 애그리거트/값 객체/낙관적 락, N+1 방지 규칙, 상태 머신 | §5, §7 |
| E-Commerce 서비스 개발 | 주문·컷오프·약속 배송창·취소 흐름 | §2, §5.1 |
| 빌드/테스트/배포 자동화 | GitHub Actions(빌드→테스트→이미지→Compose 스모크), 태그 기반 릴리스 | §14 |
| NoSQL(Redis) 대용량 처리 | GEO 인덱스, 분산 락, 멱등 키, 레이트 리밋, 실시간 라우트 상태 | §7.2 |
| Agile | Phase별 인크리먼트, 각 Phase가 실행 가능한 데모 | §15 |

### 1.3 목표 (Goals)

- G1. 주문 1건이 **API → 이벤트 → 웨이브 → 최적화된 라우트 → 배송 완료**까지 흐르는 완결된 데모.
- G2. 룰과 비용 파라미터를 **코드 변경 없이** 바꾸면 라우트 결과가 달라지고, **왜 그 결과가 나왔는지** 운영자가 설명을 볼 수 있다.
- G3. 성수기 시나리오(평시 대비 5배 주문)에서 **주문 접수는 느려지지 않고**, 계획 지연은 **측정·경고·열화(degrade)** 된다.
- G4. 어떤 컴포넌트를 죽여도 데이터가 유실되거나 중복 배송되지 않는다(at-least-once + 멱등).
- G5. 모든 설계 결정에 근거(ADR)와 측정치(벤치마크)가 붙어 있다 — Staff 레벨의 판단력을 문서로 증명.

### 1.4 비목표 (Non-goals)

- 실제 결제·재고·지도 API 연동 (모두 어댑터 인터페이스 뒤의 스텁으로 대체, 교체 가능 구조만 증명)
- 다중 리전·다중 국가, 관세/국제 배송
- 기사용 모바일 앱 (시뮬레이터가 기사 스캔 이벤트를 대신 발생)
- 정밀 도로 네트워크 라우팅 (하버사인 × 도로계수 기본, OSRM 어댑터는 선택)
- 정확한 실서비스 규모 재현 (규모는 "노트북에서 재현 가능한 축소 모델 + 확장 경로 문서화")

---

## 2. 도메인 개요

### 2.1 용어

| 용어 | 정의 |
|---|---|
| FC (Fulfillment Center) | 재고를 보관·피킹하는 물류센터. 여러 캠프에 물량을 공급 |
| Camp (배송캠프) | 라스트마일 출발 거점. 차량·기사가 소속됨 |
| Zone (권역) | 캠프 하위의 배송 구역. geohash 5자리 prefix 집합으로 정의 |
| Service Tier | `DAWN`(새벽), `SAME_DAY`(당일), `NEXT_DAY`(익일) |
| Cutoff | 티어별 주문 마감 시각. 마감 시 해당 웨이브가 닫힘 |
| Wave | (캠프, 티어, 컷오프) 단위로 묶인 주문 집합. 계획(planning)의 단위 |
| Route | 차량·기사 1회 출발의 배송 계획. 순서 있는 Stop 목록 |
| Stop | 하나의 배송지 방문. 같은 주소의 여러 주문은 하나의 Stop으로 통합 |
| Promised Window | 고객에게 약속한 배송 시간창 (SLA) |
| Candidate | 웨이브에 편입되어 디스패치 계획을 기다리는 주문 |
| Plan | 하나의 웨이브에 대한 최적화 실행 1회와 그 결과(라우트 집합, 미배정, 비용, 설명) |

### 2.2 서비스 티어와 컷오프 (기본값, 설정 가능)

| 티어 | 주문 컷오프 | 약속 배송창 | 웨이브 개수/일 |
|---|---|---|---|
| DAWN | 전일 24:00 | 익일 00:00–07:00 | 1 |
| SAME_DAY | 10:00, 14:00 | 컷오프 + 6시간 이내 | 2 |
| NEXT_DAY | 24:00 | 익일 08:00–22:00 | 1 |

### 2.3 엔드투엔드 흐름

```
고객/시뮬레이터
   │ POST /orders (Idempotency-Key)
   ▼
[order-service] ──order.placed──▶ [fulfillment-service]
                                    │ FC 선택 · 캠프/권역 결정 · 웨이브 편입
                                    ├──fulfillment.planned──▶ [dispatch-service] (후보 적재)
                                    │ (컷오프 스케줄러, 분산 락)
                                    └──wave.closed──────────▶ [dispatch-service]
                                                                │ 룰 엔진 → 최적화 → 라우트
                                                                ├──route.assigned──▶ [tracking-service]
                                                                └──order.dispatched─▶ [order-service] (상태 갱신)
시뮬레이터 ──기사 스캔(arrived/completed/failed)──▶ [tracking-service]
                                                      ├──delivery.status──▶ [order-service], [ops-api]
                                                      └──delivery.at-risk─▶ [dispatch-service] (부분 재계획)
모든 이벤트 ──▶ [ops-api] 읽기 모델(프로젝션) ──▶ [ops-web] 운영 콘솔
```

---

## 3. 시스템 아키텍처

### 3.1 컨텍스트 다이어그램

```
┌──────────────┐     ┌──────────────┐
│  sim-runner  │     │   ops-web    │  (React, 운영자)
│ (부하/기사   │     └──────┬───────┘
│  시뮬레이터) │            │ REST(JWT)
└──────┬───────┘     ┌──────▼───────┐
       │ REST        │   ops-api    │──읽기 모델(PG)
       │             └──────┬───────┘
       │                    │ 커맨드(REST) / 이벤트 구독
┌──────▼───────┐  ┌─────────▼────────┐  ┌──────────────┐  ┌──────────────┐
│order-service │  │fulfillment-svc   │  │dispatch-svc  │  │tracking-svc  │
│  PG · Redis  │  │  PG · Redis(GEO) │  │  PG · Redis  │  │  PG · Redis  │
└──────┬───────┘  └─────────┬────────┘  └──────┬───────┘  └──────┬───────┘
       └───────────────┬────┴──────────────────┴─────────────────┘
                       ▼
              Apache Kafka 4.x (KRaft) — 도메인 이벤트 버스
                       │
        Prometheus · Grafana · Tempo(OTel) — 관측성 스택
```

### 3.2 서비스 책임

| 서비스 | 책임 | 소유 데이터 | 발행 이벤트 | 구독 이벤트 |
|---|---|---|---|---|
| order-service | 주문 접수·검증·취소, 멱등 처리, 주문 상태 조회 | orders, order_items | order.placed, order.cancelled | order.dispatched, delivery.status |
| fulfillment-service | FC 선택, 캠프/권역 배정, 웨이브 수명주기·컷오프 | fulfillment_centers, camps, zones, inventory(stub), waves | fulfillment.planned, wave.closed | order.placed, order.cancelled |
| dispatch-service | 룰 엔진, 최적화, 라우트/차량/기사 관리, 재계획 | vehicles, drivers, candidates, plans, routes, rules | route.assigned, order.dispatched, plan.completed, plan.failed | fulfillment.planned, wave.closed, delivery.at-risk |
| tracking-service | 배송 진행 상태, ETA, 지연 위험 감지 | shipments, shipment_events | delivery.status, delivery.at-risk | route.assigned |
| ops-api | CQRS 읽기 모델, KPI, 운영자 수동 개입 커맨드 | rm_* (프로젝션) | (없음, 커맨드는 REST로 각 서비스 호출) | 전체 |
| sim-runner | 주문 생성 부하, 기사 이동·스캔 시뮬레이션, 시나리오 실행 | (없음) | (REST 호출만) | — |

### 3.3 서비스 간 의존성 규칙

1. **쓰기 경로는 이벤트만** 사용한다. 서비스 A가 서비스 B의 상태를 바꾸려면 이벤트를 발행하거나(도메인 사실), ops-api의 커맨드 REST를 통한다(운영자 의도).
2. **동기 REST 조회**는 ops-api → 코어 서비스 방향만 허용한다. 코어 서비스끼리는 동기 호출하지 않는다. 필요한 데이터는 이벤트 페이로드에 포함(스냅샷)하거나 자기 DB에 프로젝션한다.
3. 각 서비스는 **자기 DB(스키마)만** 접근한다. 다른 서비스 테이블에 대한 JOIN·FK는 금지.
4. 이벤트 계약은 `contracts/events/`에서 JSON Schema로 관리하고, 발행자·소비자 모두 계약 테스트를 가진다.
5. 의존성 방향은 항상 **상류(주문) → 하류(배송)** 이고, 하류가 상류에 알리는 것은 "상태 통지" 이벤트뿐이다. 순환 의존은 ArchUnit + 계약 테스트로 차단한다.

### 3.4 서비스 내부 아키텍처: 헥사고날

모든 서비스는 동일한 패키지 레이아웃을 따른다 (ArchUnit으로 강제).

```
com.dawnline.<service>
├── domain/            # 엔티티·값 객체·도메인 서비스·도메인 이벤트 (Spring 의존 금지)
├── application/       # 유스케이스(포트 in), 포트 out 인터페이스, 트랜잭션 경계
│   ├── port/in/
│   └── port/out/
├── adapter/
│   ├── in/web/        # REST 컨트롤러, DTO, 검증
│   ├── in/messaging/  # Kafka 리스너 → 유스케이스 호출 (멱등 처리 포함)
│   ├── out/persistence/  # JPA 엔티티 매핑, 리포지토리 구현
│   ├── out/messaging/    # Outbox 기록 → 릴레이 발행
│   └── out/redis/
└── config/
```

의존 규칙: `adapter → application → domain`. `domain`은 어떤 상위 패키지도 참조하지 않는다. JPA 어노테이션은 `adapter/out/persistence`의 엔티티에만 두고, 도메인 모델과 분리한다(ADR-007로 확정).

---

## 4. 이벤트 설계

### 4.1 토픽 목록

토픽 명명: `dawnline.<도메인>.<이벤트>.v<major>`. 파티션 수는 로컬 기본 12 (프로덕션 확장 경로는 §8.2).

| 토픽 | 키 | 발행자 | 소비자 | 의미 |
|---|---|---|---|---|
| dawnline.order.placed.v1 | orderId | order | fulfillment, ops | 주문 접수 완료 |
| dawnline.order.cancelled.v1 | orderId | order | fulfillment, dispatch, ops | 취소 (디스패치 전에만 허용) |
| dawnline.fulfillment.planned.v1 | orderId | fulfillment | **order**, dispatch, ops | FC·캠프·권역·웨이브 결정 |
| dawnline.wave.closed.v1 | campId | fulfillment | dispatch, ops | 컷오프 도달, 계획 시작 신호 |
| dawnline.route.assigned.v1 | routeId | dispatch | tracking, ops | 라우트 확정 (stops 포함) |
| dawnline.order.dispatched.v1 | orderId | dispatch | order, ops | 주문이 라우트에 배정됨 |
| dawnline.plan.completed.v1 | waveId | dispatch | **fulfillment**, ops | 웨이브 계획 완료 (Plan `PUBLISHED` 도달) |
| dawnline.plan.failed.v1 | waveId | dispatch | **fulfillment**, ops | 계획 실행 실패 (§5.3 Plan `FAILED` — 예외·시간초과) |
| dawnline.delivery.status.v1 | routeId | tracking | order, ops | ARRIVED/COMPLETED/FAILED |
| dawnline.delivery.at-risk.v1 | routeId | tracking | dispatch, ops | 지연 위험 감지 |
| `<topic>.dlq` | 원본 키 | 각 소비자 | 운영자 | 재처리 실패 메시지 |

### 4.2 이벤트 봉투 (Envelope)

```json
{
  "eventId": "0190b1f2-6c1a-7c3e-9b8e-3a1f0d2c4e5f",
  "eventType": "order.placed",
  "schemaVersion": 1,
  "occurredAt": "2026-08-29T01:23:45.678Z",
  "producer": "order-service",
  "partitionKey": "0190b1f2-...",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "payload": { }
}
```

- `eventId`는 UUIDv7 (시간순 정렬 가능, 멱등 키로 사용).
- Kafka 헤더에 `traceparent`(W3C), `eventType`, `schemaVersion`을 중복 기록해 페이로드를 열지 않고도 라우팅·필터링이 가능하게 한다.
- 직렬화는 JSON. 스키마는 `contracts/events/<eventType>.v<major>.schema.json` (ADR-003).

### 4.3 핵심 페이로드

**order.placed.v1**
```json
{
  "orderId": "…", "customerId": "…", "serviceTier": "DAWN",
  "address": { "line": "…", "postalCode": "06236", "lat": 37.4979, "lng": 127.0276, "geohash7": "wydm6d6" },
  "promisedWindow": { "start": "2026-08-30T00:00:00+09:00", "end": "2026-08-30T07:00:00+09:00" },
  "parcel": { "weightG": 1200, "volumeCm3": 8000, "requiresCold": false, "hazmat": false },
  "items": [ { "sku": "SKU-1001", "qty": 2 } ],
  "placedAt": "…",
  "cutoffAt": "2026-08-30T00:00:00+09:00"
}
```
`cutoffAt` 은 order-service 가 §2.2 표를 `(티어, 접수 시각)` 에 적용해 계산한 값이다.
fulfillment-service 는 웨이브 키 `(campId, tier, cutoffAt)` 에 이 값을 **그대로 쓰고 다시 계산하지
않는다** (§5.2). {@code orders} 테이블에는 저장하지 않는다 — 접수 이후 order-service 가 쓰는 곳이
없고, 필요한 쪽으로 가는 통로가 이 이벤트다.

**fulfillment.planned.v1** — order.placed 스냅샷 + `fcId, campId, zoneId, waveId, waveCutoffAt, promiseRevised`

`promiseRevised` 는 `promisedWindow` 가 접수 시점의 약속과 다른지를 말한다(ADR-020). `true` 면 이
주문은 grace(기본 90초)를 넘겨 도착해 원래 약속받은 웨이브에 들어가지 못했고, `promisedWindow` 는
새 웨이브 기준으로 개정된 값이다. order-service 는 이것을 받아 `promised_start/end` 를 갱신한다.
`outcome=UNSERVICEABLE` 에는 없다 — 배차되지 못한 주문에는 개정할 약속이 없다.

**wave.closed.v1**
```json
{ "waveId": "…", "campId": "…", "serviceTier": "DAWN", "cutoffAt": "…", "orderCount": 4820, "closedAt": "…" }
```

**route.assigned.v1**
```json
{
  "routeId": "…", "planId": "…", "waveId": "…", "campId": "…",
  "vehicleId": "…", "driverId": "…", "strategy": "greedy-nn+2opt",
  "summary": { "stopCount": 96, "distanceM": 41200, "durationS": 15840, "costKrw": 138400 },
  "stops": [
    { "seq": 1, "orderIds": ["…"], "lat": 37.49, "lng": 127.02, "plannedArrival": "…", "serviceSeconds": 90 }
  ]
}
```

**plan.completed.v1** ([ADR-024](adr/ADR-024-plan-completed-event.md))
```json
{
  "planId": "…", "waveId": "…", "campId": "…",
  "strategy": "sweep-greedy-nn+ls", "mode": "FULL",
  "routeCount": 12, "assignedCount": 4780, "unassignedCount": 40,
  "totalCostKrw": 1638000, "planDurationMs": 18420
}
```

웨이브의 계획이 끝났다는 **웨이브 단위** 신호다. `route.assigned` 는 라우트 단위라 "웨이브가
언제 계획됐는가" 에 답할 수 없다(첫 라우트인가 전부인가). 발행은 Plan 이 `PUBLISHED` 에 도달할
때 라우트 발행과 **같은 outbox 트랜잭션**이다. §6.8 의 부분 재계획은 이 이벤트를 다시 내지
않는다 — 의미는 「최초 전체 계획의 완료」로 고정된다. 계획은 성공했는데 일부 주문이 배정되지
않은 것은 `unassignedCount` 가 나르며, 그것은 `plan.failed` 가 아니다.

### 4.4 전달 보장: Outbox + at-least-once + 멱등 소비자 (ADR-006)

- **발행**: 도메인 변경과 `outbox_events` INSERT를 같은 DB 트랜잭션에서 수행. 별도 릴레이(`OutboxRelay`, 폴링 100ms, 배치 500, `FOR UPDATE SKIP LOCKED`)가 Kafka로 발행 후 `published_at` 기록. SKIP LOCKED 는 다중 인스턴스에서의 중복 발행을 막지만, 같은 `partition_key` 의 행이 서로 다른 인스턴스에서 발행되면 §4.5의 키 단위 순서가 깨질 수 있다. 따라서 릴레이는 **서비스당 단일 활성 인스턴스**를 전제로 한다. 현 단계는 인스턴스 1개로 이 전제가 자동 충족되며, 스케일아웃(Phase 3) 전에 리더 락(Redis `SET NX` + 주기 갱신, 락 상실 시 발행 중단)을 도입한다 — 그 시점에 ADR 작성. SKIP LOCKED 는 리더 전환 경합의 안전망으로 유지한다.
- **소비**: 리스너는 `processed_events(event_id, consumer)`를 먼저 INSERT(같은 트랜잭션)한다. 이미 있으면 처리 생략. 비즈니스 로직 + processed 기록 + 자기 outbox 기록이 하나의 트랜잭션.
- **`processed_events` 보존: 14일** (일 1회 배치 삭제, outbox 7일 정리와 같은 정리 스케줄러 — §7.1). 근거: 재전달 가능 창의 상한은 본 토픽 보존 7일(오프셋 리셋 포함)이며 14일은 그 2배 여유다. DLQ 보존 30일은 이 창과 무관하다 — DLQ 에 들어간 이벤트는 처리 트랜잭션이 롤백된 것이므로 `processed_events` 에 성공 기록이 없고, replay 의 안전성이 이 테이블에 의존하지 않는다. **경고: 이 논거는 "성공 처리된 이벤트는 DLQ 에 들어가지 않는다" 는 §4.6의 구조에 의존한다. DLQ 적재 경로를 바꾸는 변경은 이 보존 기간을 재검토해야 한다.**
- Kafka 트랜잭션/EOS는 사용하지 않는다. 이유·대안은 ADR-006.
- 릴레이 지연(`dawnline_outbox_lag_seconds`)과 미발행 건수는 핵심 메트릭.

### 4.5 순서 보장과 파티셔닝

- 같은 키(orderId, routeId, campId)의 이벤트는 같은 파티션 → 순서 보장. 서로 다른 키 간 순서는 보장하지 않으며, 소비자는 이를 전제로 설계한다(예: `order.cancelled`가 `fulfillment.planned`보다 먼저 올 수 있음 → 상태 머신으로 흡수).
- `wave.closed`는 campId 키로 발행되어 같은 캠프의 웨이브 계획이 직렬화된다. 캠프 단위 병렬성이 계획 처리량의 상한이며, 이는 의도된 설계다(§6.7).

### 4.6 재시도 / DLQ

| 상황 | 처리 |
|---|---|
| 일시적 오류 (DB 타임아웃, Redis 연결) | 리스너 내 지수 백오프 재시도 3회 (200ms·1s·5s) |
| 역직렬화 실패/스키마 불일치 | 즉시 DLQ + 알림 |
| 비즈니스 규칙 위반 (예: 취소 불가 상태) | DLQ 아님. 무시하고 `warn` 로그 + 메트릭 (`dawnline_event_rejected_total{reason}`) |
| DLQ 재처리 | ops-api `POST /admin/dlq/{topic}/replay` (운영자 확인 후) |

위 표는 **소비 측**이다. 발행 측에는 DLQ가 없다 — 아직 브로커에 나가지 못한 이벤트이므로 보낼 곳이 없다.

**발행 측 실패 (Outbox 릴레이)**

릴레이는 실패를 두 종류로 구분한다.

| 종류 | 예 | 처리 |
|---|---|---|
| 결정적(deterministic) | 봉투 조립·eventType 검증·직렬화 실패 | 해당 행을 즉시 격리(`failed_at` 기록, `publish_attempts` 증가), `error` 로그 + `dawnline_outbox_failed` 증가, **다음 행 계속 진행** |
| 일시적(transient) | 브로커 연결 불가, 타임아웃, `KafkaException` | 격리하지 않는다. 그때까지의 진행분을 커밋하고 백오프 후 다음 폴링에서 재시도 (`publish_attempts` 증가) |

구분 기준은 **단계**와 **Kafka 자신의 재시도 가능 여부**다.

- **조립 단계**(Kafka `send()` 이전, 저장된 바이트만 읽는 구간)의 실패는 정의상 결정적이다. 같은 행을 다시 읽으면 같은 예외가 난다.
- **전송 단계**는 Kafka 의 `RetriableException` 마커를 따른다. 재시도 가능이면 일시적, 브로커가 돌려준 오류인데 재시도 가능이 아니면 결정적(`InvalidTopicException`·`TopicAuthorizationException`·`RecordTooLargeException` 등), 직렬화 실패도 결정적.
- Kafka 가 분류하지 않은 예외(IO, 프로듀서 상태 오류)는 판단 근거가 없으므로 일시적으로 취급한다(격리는 사람의 개입을 요구하므로 보수적으로).

예외 타입을 손으로 나열하지 않는 이유는 그 목록이 반드시 불완전해지기 때문이다. 빠뜨린 비재시도 예외 하나가 곧바로 head-of-line blocking 으로 돌아온다.

이 구분이 필요한 이유는 두 실패의 성질이 정반대이기 때문이다. 결정적 실패는 **몇 번을 재시도해도 같은 결과**라서, 재시도를 유지하면 그 행이 `created_at` 순서상 맨 앞에 서서 뒤의 모든 이벤트를 영구히 막는다(head-of-line blocking). 일시적 실패는 반대로 **기다리면 풀린다** — 여기서 행을 격리하면 브로커가 잠깐 흔들렸다는 이유로 멀쩡한 이벤트가 사람 손을 기다리게 된다.

격리는 §4.5의 순서 보장을 **그 파티션 키에 한해** 깨뜨린다. 격리된 행 뒤에 같은 키의 이벤트가 있으면 그것이 먼저 발행된다. 이는 의도된 것이다 — 대안은 서비스 전체의 이벤트 발행이 멈추는 것이고, 격리는 알림(§9.4)과 함께 사람에게 넘어간다.

`publish_attempts` 는 **그 행에 대해 `send` 가 실제로 시도된 횟수**다. 일시적 실패로 배치가 중단되면 시도되지 않은 뒤 행들은 증가하지 않으며, 이는 의도된 의미다 — 브로커 장애의 관측은 이 컬럼이 아니라 `dawnline_outbox_lag_seconds`·`dawnline_outbox_unpublished` 가 담당한다.

격리된 행의 복구는 수동이다: 원인 수정 → `UPDATE outbox_events SET failed_at = NULL, publish_attempts = 0 WHERE id = …` (RB-05). ops-api 격리 조회·재큐 엔드포인트는 Phase 6 범위(§5.5 커맨드 목록에 추가).

### 4.7 스키마 진화 규칙

- 같은 major 안에서는 **추가만** 허용(필드 추가, enum 값 추가). 소비자는 알 수 없는 필드를 무시해야 한다(`FAIL_ON_UNKNOWN_PROPERTIES=false`).
- 필드 삭제·의미 변경·타입 변경은 새 토픽 `v2`로 발행하고 소비자가 이관될 때까지 v1·v2 병행 발행(dual-publish).
- 계약 테스트: 발행자는 스키마 검증, 소비자는 `contracts/events/examples/*.json`으로 역직렬화 테스트.
- **소비자가 먼저 정의하는 계약**: 소비자가 발행자보다 먼저 만들어지는 경우(예: order-service 의 `order.dispatched`·`delivery.status` 리스너는 Phase 1, 발행자는 Phase 3·5), 소비자가 자신이 읽는 최소 필드로 스키마와 예시를 먼저 정의한다. 요구사항을 가진 쪽이 소비자이기 때문이며, 발행자는 그 계약을 만족시키되 필요한 필드를 위 규칙대로 추가만 한다. 리스너 통합 테스트는 예시 이벤트를 직접 발행해 돌리므로 발행자 없이 완결된다.

---
## 5. 서비스 상세 설계

### 5.1 order-service

**책임**: 주문 접수·검증·멱등 처리, 취소, 상태 조회. 피크에 가장 먼저 맞는 서비스이므로 **쓰기 경로를 최소화**한다(INSERT 2건 + 커밋, 외부 호출 없음).

**API**

| 메서드 | 경로 | 설명 | 비고 |
|---|---|---|---|
| POST | /api/v1/orders | 주문 생성 | `Idempotency-Key` 헤더 필수, 201/200(중복) |
| GET | /api/v1/orders/{id} | 주문 상세·상태 타임라인 | |
| POST | /api/v1/orders/{id}/cancel | 취소 | `PLACED`·`PLANNED` 상태에서만 허용 |
| GET | /api/v1/orders?customerId&status&from&to | 목록(커서 페이지네이션) | |
| GET | /actuator/health/readiness | 레디니스 | DB·Kafka 프로듀서 상태 포함 |

API 버전은 URL 세그먼트 `v1` 기본. Spring Framework 7의 API Versioning 기능을 쓰되 URL 방식으로 통일한다(ADR-009).

**도메인 모델**

- 애그리거트 `Order` (루트) — `OrderItem`(엔티티), `DeliveryAddress`(VO, geohash 포함), `Parcel`(VO: weightG, volumeCm3, requiresCold, hazmat), `PromisedWindow`(VO), `ServiceTier`(enum)
- 도메인 서비스 `TierEligibility` — 주소·시각 기준 티어 가능 여부(권역 스텁 조회)
- `Geocoder` (port out) — 기본 구현은 우편번호 → 좌표 조회 테이블 + 난수 지터. 교체 가능.

**상태 머신**

진행 축:  PLACED(0) ──▶ PLANNED(1) ──▶ DISPATCHED(2) ──▶ DELIVERED·FAILED(3)      CANCELLED(축 밖)

```
PLACED ─(fulfillment.planned)─▶ PLANNED ─(order.dispatched)─▶ DISPATCHED ─(delivery COMPLETED)─▶ DELIVERED
                                                                         └(delivery FAILED)───▶ FAILED

건너뜀 (앞으로 가는 전이는 전부 허용):
  PLACED  ─(order.dispatched 가 먼저)──────────────────────────────────────────────────▶ DISPATCHED
  PLACED  ─(delivery COMPLETED/FAILED 가 먼저)────────────────────────────────────────▶ DELIVERED/FAILED
  PLANNED ─(delivery COMPLETED/FAILED 가 먼저)────────────────────────────────────────▶ DELIVERED/FAILED

취소 (이벤트가 아니라 명령):
  PLACED·PLANNED ─ cancel ─▶ CANCELLED                   (DISPATCHED 이후 취소 불가 → 409)

역행 (예: DELIVERED 인데 order.dispatched 도착):
  전이하지 않고 stale 로 세고 버린다 — dawnline_event_stale_total
```

**건너뜀은 정식 전이다.** 예외 처리가 아니라 표에 있는 경로이고, 규칙은 하나다 —
<strong>진행 축에서 앞으로 가는 전이는 전부 허용한다.</strong> 배송이 끝났다면 배송은 시작된 것이고,
중간 상태를 거치지 않았다는 것은 그 사건을 알리는 메시지가 아직 안 왔다는 뜻일 뿐이다.
**사실은 이미 일어났고, 순서가 다른 것은 우리가 알게 된 순서일 뿐이다.**

`PLACED` 에서 곧바로 건너뛰는 경로까지 여는 이유: order-service 가 소비하는 세 이벤트
(`fulfillment.planned`·`order.dispatched`·`delivery.status`)는 **서로 다른 토픽**이라 셋 사이의
순서가 보장되지 않는다(§4.5). `order.dispatched` 와 `fulfillment.planned` 는 둘 다 orderId 키지만
토픽이 다르므로 같은 파티션이 아니다. 즉 `fulfillment.planned` 가 늦으면 주문은 `PLACED` 인 채로
그 뒤의 이벤트를 먼저 받는다. `PLANNED → DELIVERED` 만 열고 `PLACED → DELIVERED` 를 닫아 두면
같은 결함이 한 칸 앞에 그대로 남는다.

**순서 뒤바뀜 흡수 (ADR-017).** `order.dispatched` 는 orderId 키, `delivery.status` 는 routeId 키로
발행된다(§4.1). 서로 다른 파티션이므로 §4.5에 따라 둘 사이의 순서는 보장되지 않는다. 그래서
`PLANNED → DELIVERED` 와 `PLANNED → FAILED` 를 정식 전이로 둔다 — "배송이 완료됐다면 배송이
시작된 것"이 사실이므로 의미적으로도 맞다. 뒤늦게 도착한 `order.dispatched` 는 아래 규칙으로 무시된다.

리스너가 전이를 시도한 결과는 셋 중 하나다.

| 상황 | 판정 | 처리 |
|---|---|---|
| 표에 있는 전이 | 적용 | 상태 변경 후 커밋 |
| 이미 지나온 지점으로의 전이 (진행 단계가 현재보다 앞) | **철 지난 이벤트** | 무시하고 커밋. `debug` 로그 + `dawnline_event_stale_total{consumer,eventType}`. DLQ 아님 |
| 그 밖의 전이 (예: `CANCELLED` 인데 `order.dispatched` 도착) | **비즈니스 규칙 위반** | 무시하고 커밋. `warn` + `dawnline_event_rejected_total{reason}` (§4.6 3행). DLQ 아님 |

stale 을 <em>세는</em> 이유는 알림이 아니라 관찰이다. 순서 뒤바뀜은 정상이지만 그 빈도가 갑자기
늘면 어딘가 지연이 커졌다는 신호이고, `rejected`(사람이 봐야 하는 상황)와 섞이면 그 신호가 묻힌다
(§9.1).

"진행 단계"는 `PLACED(0) → PLANNED(1) → DISPATCHED(2) → DELIVERED·FAILED(3)` 순서다.
`CANCELLED` 는 이 축에 있지 않다. 다만 그 이유는 "잘못된 상황이라서" 가 아니다 —
**설계된 경합 창**이다(2026-09-05 정정, ADR-017 후속 정정).

취소는 `PLACED`·`PLANNED` 에서 허용되고, `PLANNED` 는 웨이브가 `CLOSED` 된 뒤에도 유지된다.
그래서 dispatch 가 계획을 발행한 순간부터 order-service 가 `order.dispatched` 를 소비하기까지의
몇 초 동안, 취소가 **정상적으로 성공한다.** 그 뒤에 도착하는 `order.dispatched` 는 버그가 아니라
그 창의 산물이다.

order-service 가 무시하고 메트릭으로 남기는 처리는 그대로 옳다. 바뀌는 것은 그 메트릭의 뜻이다 —
이상 징후가 아니라 **이 경합 창의 크기를 재는 값**이고, 창을 줄이거나 없애는 일은 dispatch 가
소유한다([ADR-026](adr/ADR-026-dispatch-cancellation-window.md), §6.10). 축 밖에 두는 이유는 그것이
stale 로 조용히 흡수되면 그 크기를 볼 수 없기 때문이다.

그 창의 **반대쪽 끝**은 `dawnline_cancel_too_late_total` 이 센다 — 이쪽이 "취소된 주문에 배차가
왔다" 를 세고 저쪽이 "배송된 주문에 취소가 왔다" 를 센다. 두 값은 같은 창의 양 끝이라 함께 본다.

**테이블**

```sql
CREATE TABLE orders (
  id               UUID PRIMARY KEY,                 -- UUIDv7
  customer_id      UUID NOT NULL,
  service_tier     VARCHAR(16) NOT NULL,
  status           VARCHAR(16) NOT NULL,
  address_line     TEXT NOT NULL,
  postal_code      VARCHAR(10) NOT NULL,
  lat              NUMERIC(9,6) NOT NULL,
  lng              NUMERIC(9,6) NOT NULL,
  geohash7         CHAR(7) NOT NULL,
  promised_start   TIMESTAMPTZ NOT NULL,
  promised_end     TIMESTAMPTZ NOT NULL,
  weight_g         INTEGER NOT NULL,
  volume_cm3       INTEGER NOT NULL,
  requires_cold    BOOLEAN NOT NULL DEFAULT FALSE,
  hazmat           BOOLEAN NOT NULL DEFAULT FALSE,
  failure_reason   VARCHAR(24),                      -- 배차 불가 사유 (§5.2 6단계). 배달 실패에는 없다
  version          BIGINT NOT NULL DEFAULT 0,        -- 낙관적 락
  placed_at        TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_orders_customer_placed ON orders (customer_id, placed_at DESC);
CREATE INDEX ix_orders_status_placed   ON orders (status, placed_at);

CREATE TABLE order_items (
  order_id UUID NOT NULL REFERENCES orders(id),
  line_no  SMALLINT NOT NULL,
  sku      VARCHAR(32) NOT NULL,
  qty      INTEGER NOT NULL CHECK (qty > 0),
  PRIMARY KEY (order_id, line_no)
);

-- 행이 있다는 것은 곧 "그 요청은 끝났고 응답은 이것" 이다. 처리 중 상태는 여기 없다 —
-- 그 표시는 30초 뒤 스스로 풀리는 Redis 키가 맡는다 (ADR-018). 그래서 status 컬럼이 없고,
-- 응답 두 컬럼은 NOT NULL 이다.
CREATE TABLE idempotency_keys (
  idem_key      VARCHAR(64) PRIMARY KEY,
  request_hash  CHAR(64) NOT NULL,                   -- SHA-256(요청 표준형)
  response_code SMALLINT NOT NULL,
  response_body JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL                 -- created_at + 7일 (ADR-019)
);
-- 보존 7일 정리 배치용. PK 는 idem_key 라 expires_at 범위 삭제를 돕지 못한다.
-- (불변규칙 11 — EXPLAIN 비교는 docs/benchmarks/phase1-idempotency-cleanup-index.md)
CREATE INDEX ix_idempotency_keys_cleanup ON idempotency_keys (expires_at);

-- 모든 서비스 공통 (libs/messaging 가 Flyway 스크립트 제공)
CREATE TABLE outbox_events (
  id             UUID PRIMARY KEY,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id   UUID NOT NULL,
  event_type     VARCHAR(64) NOT NULL,
  topic          VARCHAR(96) NOT NULL,
  partition_key  VARCHAR(64) NOT NULL,
  headers        JSONB NOT NULL,
  payload        JSONB NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  published_at     TIMESTAMPTZ,
  publish_attempts SMALLINT NOT NULL DEFAULT 0,
  failed_at        TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL AND failed_at IS NULL;
-- 격리 게이지(dawnline_outbox_failed)는 스크레이프 주기마다 count(*) 를 돌린다. 부분 인덱스가
-- 없으면 격리 행이 0개여도 매번 풀스캔이다 (§9.1, 불변규칙 11 — EXPLAIN 비교는 PR 참조).
CREATE INDEX ix_outbox_failed ON outbox_events (failed_at) WHERE failed_at IS NOT NULL;

CREATE TABLE processed_events (
  event_id     UUID NOT NULL,
  consumer     VARCHAR(64) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (event_id, consumer)
);
-- 보존 14일(§4.4) 정리 배치용. PK 는 (event_id, consumer) 라 processed_at 범위 삭제를 돕지 못한다.
CREATE INDEX ix_processed_events_cleanup ON processed_events (processed_at);
```

**약속 배송창은 접수 시점에 order-service 가 계산한다**

`order.placed` 는 `promisedWindow` 를 필수로 싣고(§4.3), 접수 응답도 고객에게 그 창을 알려 준다.
그러므로 창은 **접수 시점에** 정해져야 하며, 그 시점에 존재하는 서비스는 order-service 뿐이다.
값은 §2.2 표를 `(티어, 접수 시각)` 에 적용한 결과이고 클라이언트가 지정할 수 없다 —
배송 SLA 를 호출자가 정하게 두면 그것은 약속이 아니다.

| 티어 | 접수 시각 (Asia/Seoul) | 약속 배송창 |
|---|---|---|
| DAWN | 언제나 | 익일 00:00–07:00 |
| SAME_DAY | 10:00 이전 | 당일 10:00–16:00 |
| SAME_DAY | 10:00 이후 14:00 이전 | 당일 14:00–20:00 |
| SAME_DAY | 14:00 이후 | 익일 10:00–16:00 |
| NEXT_DAY | 언제나 | 익일 08:00–22:00 |

이것은 **어느 웨이브에 실릴지**와 다르다. 웨이브 편성·컷오프 판정은 fulfillment-service 의 몫이고(§5.2),
order-service 는 고객에게 한 약속만 정한다. 두 값이 어긋나면(예: 컷오프를 놓쳐 다음 웨이브로 밀림)
그것은 지연이고, 정시율(§8.1)이 그 사실을 그대로 드러낸다.

**멱등 처리 흐름 (POST /orders)** — 잠금은 Redis, 진실은 DB (ADR-018)

0. 요청의 **표준형**에서 SHA-256 지문을 만든다. 같은 키에 다른 요청이 왔는지 판정하는 기준이며,
   원문 바이트가 아니라 표준형을 쓰는 이유는 공백·필드 순서만 다른 재전송이 422 가 되면 안 되기 때문이다.
1. DB `idempotency_keys` 를 PK 로 한 번 읽는다.
   - 행이 있고 지문이 다르면 **422** (같은 키, 다른 요청)
   - 행이 있고 지문이 같으면 저장된 응답을 **200** 으로 재생
2. 행이 없으면 Redis `SET idem:order:{key} IN_PROGRESS NX PX 30000`.
   - 획득 → 3
   - 이미 있음 → **409** (다른 요청이 처리 중이고 아직 커밋되지 않았다)
   - Redis 불가 → 잠금 없이 3 으로 간다. 동시성은 `idempotency_keys` PK 가 커밋 시점에 막는다
     (성능 저하, 정확성 유지 — 불변규칙 7)
3. 트랜잭션 하나: orders + order_items + outbox(`order.placed`) + idempotency_keys
   `INSERT … ON CONFLICT (idem_key) DO NOTHING`.
   0행이면 그 사이 다른 요청이 끝냈다는 뜻이므로 롤백하고 **409**.
   `DO NOTHING` 은 충돌 상대가 아직 커밋되지 않았으면 **그 트랜잭션이 끝날 때까지 기다렸다가**
   0행을 돌려준다(측정: `docs/benchmarks/phase1-idempotency-cleanup-index.md` 와 같은 방식으로
   두 연결로 확인, 1,972 ms 대기 후 `INSERT 0 0`). 그래서 Redis 가 없어도 같은 키의 동시 요청 중
   하나만 성공한다.
4. 커밋 후 Redis 키를 `DONE` 으로 갱신(TTL 24h). 실패해도 무시한다 — 다음 요청은 1번에서 DB 로 걸린다.
   3번이 실패했다면 Redis 키를 지운다. 안 지우면 30초 동안 재시도가 409 가 된다.

**보존은 7일이고, 그것은 클라이언트와의 계약이다** (ADR-019).
`expires_at = created_at + 7일` 이며 만료된 행은 정리 배치가 지운다. 즉 **7일이 지난 멱등 키로
같은 요청을 보내면 그것은 재생이 아니라 새 주문이 된다.** Redis 키의 TTL(24h)은 그대로다 —
그 24시간은 DB 를 읽지 않고 중복을 걸러 내는 구간이고, 이후 7일까지는 DB 가 답한다.

정리 방향이 `processed_events`(보존 14일, §4.4)와 **반대**라는 점이 7일의 근거다. 저쪽은 지워도
같은 이벤트가 다시 오지 않으면 그만이지만, 이쪽은 지운 뒤 같은 키가 오면 **새 주문이 만들어진다**.
잘못 지웠을 때의 대가가 크므로 재시도 창(수 분)보다 훨씬 큰 여유를 둔다.

**Redis**: `idem:order:*`, 레이트 리밋 `rl:customer:{id}` (토큰 버킷 Lua, 기본 60 req/min).

### 5.2 fulfillment-service

**책임**: 주문마다 (FC, 캠프, 권역, 웨이브)를 결정하고, 컷오프에 웨이브를 닫는다.

**FC 선택 규칙 (순서대로 필터 → 점수)**
1. 티어 지원 여부 (`fulfillment_centers.tiers`에 포함)
2. 냉장 필요 시 `supports_cold`
3. 재고 가용 (`inventory_stock` 스텁, 모든 SKU 가용 시 통과) — 실서비스에서는 재고 서비스 연동 지점
4. 주소 geohash5 → `zones` 매핑으로 캠프 결정; 캠프의 `fc_id` 후보
5. 캠프의 홈 FC 가 1~3단계를 통과하지 못했으면 **대체 FC** 를 고른다 —
   Redis `GEOSEARCH geo:fc FROMLONLAT <캠프 좌표> BYRADIUS 50 km ASC` 로 1~3단계를 통과한 FC 중
   캠프에서 가장 가까운 것. 홈 FC 가 통과했으면 그대로 쓰고 이 단계는 건너뛴다.
6. 반경 안에 통과한 FC 가 하나도 없으면 주문을 `UNSERVICEABLE`(`NO_ELIGIBLE_FC`)로 표시하고,
   권역 자체를 찾지 못한 경우(`NO_ZONE_MATCH`)와 **따로 센다**. `fulfillment.planned` 에
   `outcome=UNSERVICEABLE` 로 발행한다 (주문 서비스는 이를 받아 상태 `FAILED`, 사유 기록)

**`UNSERVICEABLE` 사유** (권장 어휘는 `contracts/events/README.md` §4.5)

| 사유 | 언제 |
|---|---|
| `NO_FC_FOR_TIER` | 1단계 — 그 티어를 지원하는 FC 가 없다 |
| `NO_COLD_FC` | 2단계 — 냉장이 필요한데 `supports_cold` FC 가 없다 |
| `OUT_OF_STOCK` | 3단계 — 재고가 없다 |
| `NO_ZONE_MATCH` | 4단계 — geohash5 → 권역 매핑 실패 |
| `NO_ACTIVE_CAMP` | 4~5단계 — 권역은 있으나 활성 캠프가 없다 |
| `NO_ELIGIBLE_FC` | 5~6단계 — 반경 50 km 안에 1~3단계를 통과한 FC 가 없다 |
| `STALE_PLACED` | **FC 선택 전** — `cutoffAt < now − 24h`. 지각 도착 흡수 경로의 상한이다([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 후속 정정). 20일 묵은 `order.placed` 가 DLQ replay 로 들어와도 "다음 웨이브 + 약속 개정" 을 타지 않게 한다 — 그것은 유령 배송이다 |

`STALE_PLACED` 는 **다른 사유들보다 먼저** 판정한다. 컷오프가 하루를 넘긴 주문은 FC·재고를 볼
이유가 없고, 그 판정에 쓰는 비용도 아깝다.

**1~3단계와 4단계의 결과가 만나는 자리 ([ADR-021](adr/ADR-021-zone-seed-derived-from-geocoder.md))**

1~3단계는 **FC 후보 집합**을 거르고, 4단계는 주소로부터 **캠프**를 정한다. 이 문서는 오랫동안 그
둘이 어떻게 만나는지를 적지 않았다. `zones.geohash5` 가 UNIQUE 이므로 한 주소 → 한 권역 → 한 캠프
→ 홈 FC 하나이고, 그대로 읽으면 "복수 후보" 가 생길 일이 없어 5단계와 `geo:fc` 적재가 죽은 코드가
된다. 정합한 읽기는 하나뿐이다 — **5단계는 캠프의 홈 FC 가 필터에서 떨어졌을 때의 대체 선택이다.**

**거리 기준점은 고객 주소가 아니라 캠프다.** 라스트마일은 어느 FC 를 쓰든 캠프에서 출발하므로,
대체 FC 선택에서 달라지는 비용은 **FC → 캠프 간선(linehaul)** 뿐이다. 고객 주소를 기준으로 재면
어차피 캠프를 거칠 거리를 두 번 세게 된다. 반경 50 km 는 그 간선의 상한이다.

**대체가 일어났다는 것은 세는 값이다.** `dawnline_fc_fallback_total{camp,reason}` (§9.1) —
`reason` 은 홈 FC 가 떨어진 필터(`tier`/`cold`/`inventory`)다. 이 값이 계속 오르는 캠프는 홈 FC
배정이 잘못됐거나 그 FC 의 역량이 부족한 것이고, 그것이 이 규칙이 처음부터 드러내려던 사실이다.

**Wave 수명주기**

```
OPEN ──(cutoff 도달, 락 획득)──▶ CLOSING ──(wave.closed 발행 완료)──▶ CLOSED ──(plan.completed)──▶ PLANNED
                                                                        └──(plan.failed)──▶ PLAN_FAILED ──(plan.completed, 운영자 재실행)──▶ PLANNED
```
계획 완료 신호는 `route.assigned` 가 아니라 **`plan.completed`** 다([ADR-024](adr/ADR-024-plan-completed-event.md)).
`route.assigned` 는 라우트 단위라 웨이브의 완료를 말할 수 없고, 개수를 아는 것은 발행자뿐이다.
`PLAN_FAILED` 는 종결 상태가 아니다 — 운영자 재실행(§5.3)이 성공하면 `PLANNED` 로 간다.

마지막 두 전이는 **서로 다른 두 토픽**에서 오므로 순서가 뒤바뀔 수 있다(§4.5). 재실행이 있으면
1회차 `plan.failed` 가 2회차 `plan.completed` 보다 늦게 도착할 수 있고, 그대로 두면 라우트가 이미
나간 웨이브가 실패로 표시된다. 그래서 이 두 전이에만 [ADR-017](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md)
의 축 규칙을 적용한다 — `OPEN(0) → CLOSING(1) → CLOSED(2) → PLAN_FAILED(3) → PLANNED(4)`, `PLANNED`
가 흡수 상태이고 그 뒤에 온 `plan.failed` 는 무시하고 센다
(`dawnline_event_rejected_total{reason="wave_already_planned"}`, §4.6 — DLQ 아님).
앞의 세 상태는 이 서비스가 스스로 옮기므로 건너뜀은 여전히 예외다.
- 웨이브는 (campId, tier, cutoffAt)당 1개. 주문 편입 시 없으면 생성(`INSERT … ON CONFLICT DO NOTHING` 후 재조회).
- 컷오프 스케줄러: 매 30초 `cutoff_at <= now() - grace AND status='OPEN'` 조회 → 웨이브별 Redis 락 `lock:wave:{id}` (SET NX PX 60000, Lua 언락) → **`SELECT … FOR UPDATE`** → `CLOSING` 전이 + `wave.closed` outbox. 락 실패는 다른 인스턴스가 처리 중이라는 뜻이므로 스킵.
- 컷오프 이후 도착한 같은 티어 주문은 **다음 웨이브**로 편입. `CLOSING/CLOSED` 웨이브에는 편입 불가 — **편입이 웨이브 행을 `SELECT … FOR SHARE` 로 잡고 상태를 확인한 뒤 INSERT** 하므로, 그 트랜잭션이 끝나기 전에는 마감이 끼어들 수 없다([ADR-025](adr/ADR-025-wave-admission-share-lock.md)). 공유 락끼리는 막지 않아 같은 웨이브로 몰리는 편입은 병렬이다.
- **`waves.order_count` 는 편입마다 증감하지 않는다.** 마감 시 `SELECT count(*) FROM fulfillment_orders WHERE wave_id = ? AND status='PLANNED'` 로 한 번 센다(ADR-025). 그래서 취소가 카운트를 건드리는 분기가 없고, 카운터 드리프트도 구조적으로 불가능하다. 진행 중 웨이브의 편입량은 §9.1 의 `dawnline_wave_orders` 게이지가 같은 집계로 본다.

**주문 단위 상태와 취소 ([ADR-022](adr/ADR-022-fulfillment-order-aggregate.md))**

`order.placed` 와 `order.cancelled` 는 키가 같지만(orderId) **다른 토픽**이라 순서가 보장되지
않는다(§4.5). 별도의 취소 마커를 두지 않고 `fulfillment_orders` 의 한 상태로 흡수한다.

| 순서 | 웨이브 상태 | 처리 |
|---|---|---|
| 취소 선착 | — | `status=CANCELLED`, `placed_event_id=NULL` 행 생성. 뒤에 온 `order.placed` 는 무시하고 `dawnline_event_rejected_total{reason="cancelled_before_placed"}` (§4.6, DLQ 아님) |
| 취소 후착 | 웨이브 상태와 무관 | 상태만 `CANCELLED`. **카운트를 건드리지 않는다** — `order_count` 는 마감 시 `status='PLANNED'` 만 세므로(ADR-025) 마감 전 취소는 자동으로 빠지고, 마감 후 취소는 이미 나간 `wave.closed` 의 숫자를 바꾸지 않는다. 후보 제거는 §4.1 대로 dispatch 가 자기 `order.cancelled` 소비로 한다 |

두 리스너가 같은 `order_id` 로 동시에 INSERT 하면 PK 에서 한쪽이 대기한다.
`INSERT … ON CONFLICT DO NOTHING` 후 재조회하고 상태 머신을 적용한다 — ADR-018 과 같은 패턴이다.

**컷오프는 order-service 가 정하고 fulfillment 는 받아 쓴다 ([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md))**

웨이브 키는 `(campId, tier, cutoffAt)` 인데, 그 `cutoffAt` 을 여기서 다시 계산하지 않는다.
`order.placed` 가 싣고 온 값을 그대로 쓴다.

이유는 order-service 가 접수 시점에 **이미 그 값을 썼기 때문**이다. 고객에게 약속한 배송창은
§2.2 표를 `(티어, 접수 시각)` 에 적용한 결과이고, 그 계산의 중간 산물이 컷오프다. 두 서비스가
같은 스케줄 표를 각자 들고 각자 계산하면, 표를 한쪽만 고치는 날 **약속한 창과 실제로 실린 웨이브가
말없이 어긋난다.** 계산은 한 곳에서 하고, 나머지는 결과를 받는다.

`fulfillment.planned` 소비 시각으로 웨이브를 고르는 일은 없어야 한다. 그 값은 outbox 지연·소비
지연·재처리에 따라 흔들리며, 흔들리는 값으로 웨이브를 고르면 같은 주문이 재처리 때 다른 웨이브에
들어간다(멱등 소비자가 막아 주는 것은 <em>중복</em>이지 <em>다른 결과</em>가 아니다).

**약속을 깨야 할 때는 말없이 깨지 않는다 ([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md))**

위 규칙에서 새 경합이 생긴다. 09:59:59에 접수돼 10:00 컷오프 창을 약속받은 주문이 있는데,
outbox 릴레이 폴링(100ms)과 소비 지연을 거쳐 fulfillment 에 10:00:01에 도착한다고 하자.
현재 규칙("컷오프 이후 도착은 다음 웨이브, `CLOSING/CLOSED` 편입 불가")대로면 그 주문은 다음
웨이브로 밀리고, **고객은 이미 10:00–16:00을 약속받았다.** 정상 지연 하나가 약속을 조용히 깬다.

둘로 나눠 처리한다.

1. **정상 지연은 흡수한다.** 웨이브 마감은 `cutoffAt` 이 아니라 `cutoffAt + grace` 에 실행한다.
   기본 90초, 설정값. 이 값은 "outbox 지연 + 소비 지연" 의 상한을 잡은 것이고, §9.1 의
   outbox 지연 게이지가 그 상한을 넘기 시작하면 알림이 먼저 울린다. grace 동안 도착한 주문은
   약속받은 그 웨이브에 그대로 들어간다.
2. **흡수하지 못하면 개정 사실을 되돌려 알린다.** grace 를 넘겨 도착해 이미 `CLOSED` 인 웨이브의
   `cutoffAt` 을 가진 주문은 다음 웨이브에 넣되, `fulfillment.planned` 에 **개정된**
   `promisedWindow` 와 `promiseRevised: true` 를 실어 보낸다(additive, §4.7). order-service 는
   그것을 받아 자기 `promised_start/end` 를 갱신한다 — 그러려면 애그리거트에 약속창을 바꾸는
   메서드가 필요하다(현재 `Order.promisedWindow` 는 불변이다. 불변규칙 6에 따라 세터가 아니라
   `revisePromise(window, at)` 같은 메서드로 연다).

**왜 이 문단이 지금 여기 있는가**

이것은 웨이브 구현의 세부가 아니라 **서비스 경계를 가로지르는 약속의 소유권** 문제다.
약속은 상류(order-service)가 하고, 그 약속을 지킬 수 있는지는 하류(fulfillment-service)가 안다.
하류가 지키지 못하게 됐을 때 선택지는 셋뿐이다 — 조용히 깬다(고객이 나중에 알게 된다),
접수를 거절한다(이미 201을 준 뒤라 불가능하다), **개정 사실을 상류로 되돌려 알린다.**
셋째만이 정직하고, 그래서 `promiseRevised` 가 필요하다. 정시율(§8.1)도 개정된 창을 기준으로
재는 것이 아니라 **원래 약속과 개정 횟수를 함께** 봐야 의미가 있다.

**Phase 2 에서 함께 만들 관측 지표** (§9.1 에 예약해 두었다)

- `dawnline_promise_revised_total{camp,tier}` — 개정 횟수. 이 값이 0 이 아니라는 것은 grace 로
  흡수하지 못한 지연이 있었다는 뜻이고, 늘어나면 grace 를 늘릴 것이 아니라 지연의 원인을 봐야 한다.
- 정시율은 **원 약속 기준**과 **개정 약속 기준** 두 값을 따로 낸다
  (`dawnline_delivery_on_time_ratio{basis}`). 하나만 내면 개정으로 정시율을 세탁할 수 있다 —
  못 지킬 것 같으면 약속을 미루면 되기 때문이다. SLO 의 기준은 원 약속이다(§8.1).

**2026-09-05 확정**: [ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md).
여기 적어 두었던 이유는, 이 결정이 Phase 1의 "약속창을 접수 시점에 계산한다" 에서 곧바로 따라
나오기 때문이다 — 그때 정하지 않으면 Phase 2 에서 "이미 나간 약속" 을 마주하고 급하게 정하게 된다.
`grace` 는 `dawnline.fulfillment.wave.grace` 설정값이고 기본 90초다.

**테이블(핵심)**

```sql
CREATE TABLE fulfillment_centers (id UUID PK, code VARCHAR(16) UNIQUE, name TEXT, lat NUMERIC(9,6), lng NUMERIC(9,6),
  supports_cold BOOLEAN, tiers VARCHAR(16)[] NOT NULL, active BOOLEAN);
CREATE TABLE camps (id UUID PK, code VARCHAR(16) UNIQUE, fc_id UUID REFERENCES fulfillment_centers, name TEXT,
  lat NUMERIC(9,6), lng NUMERIC(9,6), active BOOLEAN);
CREATE TABLE zones (id UUID PK, camp_id UUID REFERENCES camps, code VARCHAR(16), geohash5 CHAR(5) NOT NULL UNIQUE);
CREATE TABLE inventory_stock (fc_id UUID, sku VARCHAR(32), available_qty INTEGER, PRIMARY KEY (fc_id, sku));
CREATE TABLE waves (id UUID PK, camp_id UUID NOT NULL, service_tier VARCHAR(16) NOT NULL, cutoff_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(16) NOT NULL, order_count INTEGER NOT NULL DEFAULT 0, closed_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (camp_id, service_tier, cutoff_at));
CREATE INDEX ix_waves_open_cutoff ON waves (cutoff_at) WHERE status = 'OPEN';
-- 주문 단위 애그리거트 (ADR-022). fulfillment 가 한 주문에 대해 아는 것을 전부 담는다 —
-- 어느 웨이브·FC·권역인지, 왜 UNSERVICEABLE 인지, 약속이 개정됐는지, 취소됐는지.
-- 웨이브 소속은 (wave_id IS NOT NULL AND status='PLANNED') 로 정의된다.
CREATE TABLE fulfillment_orders (
  order_id UUID PRIMARY KEY, status VARCHAR(16) NOT NULL,     -- PLANNED | UNSERVICEABLE | CANCELLED
  wave_id UUID REFERENCES waves, camp_id UUID, fc_id UUID, zone_id UUID,
  cutoff_at TIMESTAMPTZ, promised_start TIMESTAMPTZ, promised_end TIMESTAMPTZ,
  promise_revised BOOLEAN NOT NULL DEFAULT FALSE,
  unserviceable_reason VARCHAR(24), fc_fallback_reason VARCHAR(16),
  placed_event_id UUID,                                       -- NULL 이면 order.placed 가 아직 안 왔다
  cancelled_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
CREATE INDEX ix_fulfillment_orders_wave ON fulfillment_orders (wave_id);
CREATE INDEX ix_fulfillment_orders_cleanup ON fulfillment_orders (updated_at);
```

`wave_orders` 는 **V1 에만 있었고 V2 에서 드롭한다**([ADR-022](adr/ADR-022-fulfillment-order-aggregate.md)).
그 표는 주문에 대해 fulfillment 가 아는 것의 절반만 들었다 — `UNSERVICEABLE` 사유도, 약속 개정도,
취소도 담을 곳이 없어서 "주문 X 는 왜 웨이브에 없나" 에 답할 수 없었다. 그리고 복합 PK
`(wave_id, order_id)` 는 같은 주문이 <em>서로 다른 두 웨이브</em>에 들어가는 것을 막지 못한다.
`fulfillment_orders` 의 `order_id` 단독 PK 는 그것을 구조적으로 막는다.

보존은 `fulfillment_orders` **30일**(`updated_at` 기준, 종결 상태만), `waves` **90일**이다
([ADR-023](adr/ADR-023-fulfillment-retention.md)). 30일은 DLQ 보존(§7.3)과 같은 창이다 — DLQ 에
남은 `order.placed` 를 30일째에 열었을 때 fulfillment 기록이 없으면 "이 주문은 왜 웨이브에 없나"
에 답할 수 없고, 그 질문에 답하려고 만든 표가 정작 그 순간에 비어 있게 된다.

**Redis**: `geo:fc`, `geo:camp` (GEOADD, **best-effort 적재 + 주기 재시도** — 레디니스 조건이 아니다, §8.6), `zone:geohash5:{prefix}` → **`zoneId:campId`** 캐시 (TTL 10m), `lock:wave:{id}`.

권역 캐시의 값이 zoneId <em>만</em>이 아닌 이유: 호출부가 권역 다음에 곧바로 캠프를 필요로 한다(§5.2 4단계). zoneId 만 캐시하면 캐시가 맞아도 캠프를 얻으려 DB 를 한 번 더 가야 하므로 왕복이 줄지 않는다 — 캐시가 없는 것과 같아진다.

### 5.3 dispatch-service

**책임**: 후보 적재, 차량·기사 자원, 룰 엔진·최적화 실행, 라우트 확정·발행, 재계획. 알고리즘 상세는 §6.

**Plan 상태 머신**

```
REQUESTED ──▶ PLANNING ──▶ PLANNED ──▶ PUBLISHED (route.assigned·order.dispatched·plan.completed 발행)
                 └──(예외/시간초과)──▶ FAILED (plan.failed 발행, 운영자 재실행 가능)
```
- `PUBLISHED` 도달 시 라우트별 `route.assigned`·주문별 `order.dispatched` 와 함께 웨이브 단위
  `plan.completed` 를 **같은 outbox 트랜잭션**에 넣는다([ADR-024](adr/ADR-024-plan-completed-event.md)).
  나눠 넣으면 "완료라는데 라우트가 없다" 가 생긴다. 재실행이 성공하면 `plan.completed` 가 다시
  나가고, 그것이 웨이브를 `PLAN_FAILED → PLANNED` 로 되돌리는 유일한 경로다.
- `route_plans.wave_id`는 UNIQUE. `wave.closed`가 중복 도착해도 두 번째는 기존 plan을 발견하고 종료(멱등).
- 계획 중 인스턴스가 죽으면 `PLANNING` 상태로 남는다. 스타트업/스케줄러가 `PLANNING`이고 `started_at`이 10분 경과한 plan을 `REQUESTED`로 되돌려 재실행한다. 결과 쓰기는 plan 단위 트랜잭션이므로 부분 결과가 발행되지 않는다.
- **`PLANNING` 중에 도착한 `order.cancelled` 는 계획을 멈추지 않는다.** 계획은 시작 시점 스냅샷으로
  끝까지 돌고, `PUBLISHED` 직전 재검증(§6.5 6단계)이 후보 상태를 다시 읽어 취소된 것을 stop 에서
  뺀다 — 그래야 이 경합 창이 `revision` 하나를 쓰지 않고 닫힌다
  ([ADR-026](adr/ADR-026-dispatch-cancellation-window.md), §6.10).

**API**

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | /api/v1/plans/{waveId}/run?strategy=&mode= | 수동 (재)계획 실행 (운영자) |
| GET | /api/v1/plans/{planId} | 계획 결과·비용·미배정·설명 |
| GET | /api/v1/routes/{routeId} | 라우트·stop 목록 |
| POST | /api/v1/routes/{routeId}/stops/{orderId}/reassign | stop을 다른 라우트로 이동(운영자) |
| GET/PUT | /api/v1/rules | 룰 조회·수정 (버전 증가, 이력 보관) |
| GET/POST | /api/v1/vehicles, /drivers | 자원 관리 |

**테이블(핵심)**

```sql
CREATE TABLE vehicles (id UUID PK, camp_id UUID NOT NULL, type VARCHAR(16), max_weight_g INTEGER, max_volume_cm3 INTEGER,
  is_cold BOOLEAN, fixed_cost_krw INTEGER, cost_per_km_krw INTEGER, cost_per_min_krw INTEGER,
  shift_start TIME, shift_end TIME, active BOOLEAN);
CREATE TABLE drivers (id UUID PK, camp_id UUID, vehicle_id UUID, name TEXT, status VARCHAR(16));
CREATE TABLE dispatch_candidates (order_id UUID PK, wave_id UUID NOT NULL, camp_id UUID NOT NULL, zone_id UUID,
  lat NUMERIC(9,6), lng NUMERIC(9,6), geohash7 CHAR(7), weight_g INTEGER, volume_cm3 INTEGER,
  requires_cold BOOLEAN, hazmat BOOLEAN, promised_start TIMESTAMPTZ, promised_end TIMESTAMPTZ,
  priority SMALLINT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0);
CREATE INDEX ix_cand_wave ON dispatch_candidates (wave_id, status);
CREATE TABLE route_plans (id UUID PK, wave_id UUID NOT NULL UNIQUE, camp_id UUID NOT NULL, status VARCHAR(16) NOT NULL,
  strategy VARCHAR(32), mode VARCHAR(8), seed BIGINT, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
  total_cost_krw BIGINT, assigned_count INTEGER, unassigned_count INTEGER, plan_duration_ms INTEGER, version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE routes (id UUID PK, plan_id UUID REFERENCES route_plans, vehicle_id UUID, driver_id UUID, seq_no SMALLINT,
  status VARCHAR(16), stop_count INTEGER, distance_m INTEGER, duration_s INTEGER, cost_krw INTEGER, version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE route_stops (id UUID PK, route_id UUID REFERENCES routes, seq SMALLINT NOT NULL, lat NUMERIC(9,6), lng NUMERIC(9,6),
  planned_arrival TIMESTAMPTZ, planned_departure TIMESTAMPTZ, service_s INTEGER, status VARCHAR(16), UNIQUE (route_id, seq));
CREATE TABLE route_stop_orders (stop_id UUID REFERENCES route_stops, order_id UUID, PRIMARY KEY (stop_id, order_id));
CREATE TABLE dispatch_rules (id UUID PK, camp_id UUID NULL, name VARCHAR(64) NOT NULL, type VARCHAR(48) NOT NULL,
  severity VARCHAR(8) NOT NULL CHECK (severity IN ('HARD','SOFT')), params JSONB NOT NULL, priority SMALLINT NOT NULL,
  enabled BOOLEAN NOT NULL, rule_version INTEGER NOT NULL, updated_at TIMESTAMPTZ);
CREATE TABLE plan_explanations (id UUID PK, plan_id UUID NOT NULL, order_id UUID, route_id UUID, rule_name VARCHAR(64),
  outcome VARCHAR(16), detail JSONB);
CREATE INDEX ix_expl_plan_order ON plan_explanations (plan_id, order_id);
```

**Redis**: `rules:camp:{id}:v{n}` (룰셋 캐시), `route:{id}:progress` (HASH: nextSeq, completed, failed), `lock:plan:{waveId}` (이중 안전장치).

### 5.4 tracking-service

**책임**: 라우트별 배송 진행, ETA, 지연 위험 감지, 상태 통지.

- `route.assigned` 수신 → stop마다 `shipments` 생성(status `SCHEDULED`, `eta_at = planned_arrival`).
- 기사 스캔 API `POST /api/v1/routes/{id}/stops/{seq}/events` (DEPARTED_CAMP, ARRIVED, COMPLETED, FAILED, 위치 포함). 시뮬레이터가 호출.
- ETA 재계산: 현재 stop 실제 시각 − 계획 시각 = 편차 `d`. 이후 stop들의 `eta = planned + d` (단순 이동 모델; 개선 여지는 §17).
- **at-risk 규칙**: 어떤 stop의 `eta > promised_end − 15분`이면 `delivery.at-risk` 1회 발행(라우트당 5분 쿨다운, Redis `SET NX`). 페이로드에 남은 stop 목록·편차 포함.
- 상태 머신: `SCHEDULED → OUT_FOR_DELIVERY → ARRIVED → COMPLETED | FAILED`. 역행 이벤트는 거부(멱등).

```sql
CREATE TABLE shipments (order_id UUID PK, route_id UUID NOT NULL, stop_seq SMALLINT NOT NULL, status VARCHAR(20) NOT NULL,
  planned_arrival TIMESTAMPTZ, eta_at TIMESTAMPTZ, promised_end TIMESTAMPTZ, delivered_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0);
CREATE INDEX ix_ship_route ON shipments (route_id, stop_seq);
CREATE TABLE shipment_events (id UUID, order_id UUID, route_id UUID, type VARCHAR(20), occurred_at TIMESTAMPTZ NOT NULL,
  lat NUMERIC(9,6), lng NUMERIC(9,6), payload JSONB, PRIMARY KEY (occurred_at, id)) PARTITION BY RANGE (occurred_at);
-- 일 단위 파티션, 보존 30일 (pg_partman 없이 Flyway + 스케줄러로 생성/삭제)
```

**Redis**: `driver:{id}:pos` (GEO), `route:{id}:atrisk:cooldown`.

### 5.5 ops-api + ops-web (백오피스)

**ops-api**
- 모든 토픽을 구독해 **읽기 모델**을 갱신 (CQRS 프로젝션). 코어 서비스 DB는 절대 직접 읽지 않는다.
- 커맨드는 코어 서비스 REST로 위임: 웨이브 조기 마감, 계획 재실행, stop 재배정, 주문 홀드/취소, DLQ 재처리, **outbox 격리 행 조회·재큐**(§4.6 발행 측 실패).
- 인증: JWT(HS256, 로컬 시크릿), 역할 `OPS_VIEWER`, `OPS_OPERATOR`, `ADMIN`. 커맨드는 `OPS_OPERATOR` 이상. 모든 커맨드는 `audit_logs`에 기록.

```sql
CREATE TABLE rm_orders (order_id UUID PK, customer_id UUID, service_tier VARCHAR(16), status VARCHAR(20), camp_id UUID, wave_id UUID,
  route_id UUID, promised_end TIMESTAMPTZ, eta_at TIMESTAMPTZ, delivered_at TIMESTAMPTZ, on_time BOOLEAN, updated_at TIMESTAMPTZ);
CREATE TABLE rm_waves (wave_id UUID PK, camp_id UUID, service_tier VARCHAR(16), cutoff_at TIMESTAMPTZ, status VARCHAR(16),
  order_count INTEGER, plan_id UUID, plan_duration_ms INTEGER, total_cost_krw BIGINT, unassigned_count INTEGER);
CREATE TABLE rm_routes (route_id UUID PK, plan_id UUID, camp_id UUID, vehicle_id UUID, driver_id UUID, status VARCHAR(16),
  stop_count INTEGER, completed_count INTEGER, failed_count INTEGER, at_risk BOOLEAN, distance_m INTEGER, cost_krw INTEGER);
CREATE TABLE rm_kpi_hourly (camp_id UUID, bucket_hour TIMESTAMPTZ, orders INTEGER, dispatched INTEGER, delivered INTEGER,
  on_time INTEGER, late INTEGER, failed INTEGER, cost_krw BIGINT, PRIMARY KEY (camp_id, bucket_hour));
CREATE TABLE audit_logs (id UUID PK, actor VARCHAR(64), action VARCHAR(48), target_type VARCHAR(24), target_id UUID,
  request JSONB, result VARCHAR(16), created_at TIMESTAMPTZ);
```

**Phase 6 메모 — `rm_orders` 는 약속을 <em>두 개</em> 들어야 한다.** §8.1 의 정시율은 "고객이 처음
받은 약속" 기준으로 재는데, order-service 의 `promised_start/end` 는 개정 경로에서 **덮인다**
([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 결정 3 — 덮는 것이 맞다,
고객에게 보여 줄 값은 지금 유효한 약속이다). 그러면 원 약속을 아는 곳은 `order.placed` 이벤트뿐이고,
그것을 보관해 두 기준을 모두 낼 수 있는 곳은 **여기**다. 위 DDL 의 `promised_end` 한 칸으로는
`dawnline_delivery_on_time_ratio{basis}`(§9.1)의 두 값을 낼 수 없다 — 그 SLO 는 개정으로 정시율을
세탁할 수 없게 하려고 두 값으로 낸 것인데, 한 칸만 두면 정확히 그 세탁이 가능해진다.
Phase 2-7 에서 order-service 쪽을 구현하며 드러났다.

`rm_waves` 의 `plan_id`·`plan_duration_ms`·`total_cost_krw`·`unassigned_count` 를 채우는 것은
`plan.completed` 다([ADR-024](adr/ADR-024-plan-completed-event.md)). 이 네 칸은 웨이브 단위 값이라
`route.assigned` 로는 채울 수 없다 — 그 이벤트가 없던 동안 이 칸들에는 출처가 없었다.

**Phase 6 메모 — `plan.completed` 는 일부 `route.assigned` 보다 먼저 올 수 있다.** 같은 outbox
트랜잭션에서 나가도 토픽과 파티션이 달라 순서가 보장되지 않는다(§4.5). `rm_waves` 는
`route_count` 를 저장해 두고 뒤늦게 오는 라우트를 세는 식이어야 하며, **아직 다 오지 않았다고
실패로 표시하지 않는다.** 축 규칙으로 흡수한다.

**ops-web (React, 최소 범위)** — 화면 4개: ① 캠프 대시보드(웨이브 상태·정시율·미배정·계획 시간) ② 웨이브/계획 상세(라우트 목록, 비용, 설명 조회) ③ 라우트 지도(stop 순서 폴리라인, 진행 상태, at-risk 강조) ④ 룰 편집. 지도는 Leaflet + OpenStreetMap 타일 `[결정 필요: 타일 서버 정책상 데모 용도 확인]`.

### 5.6 sim-runner / benchmark 도구

- `sim-runner` (Spring Boot CLI, `tools/sim-runner`): 시나리오 YAML로 (a) 주문 생성기 — 캠프별 좌표 분포, 티어 비율, 냉장 비율, 초당 rps 곡선(평시·피크) (b) 기사 시뮬레이터 — `route.assigned` 구독 후 stop을 순서대로 이동하며 스캔 이벤트 호출, 지연·실패 확률 주입.
- `benchmark` (`tools/benchmark`): 고정 데이터셋(JSON)으로 `DispatchStrategy` 구현을 메모리 내에서 실행·비교 (JMH 대신 단순 반복 측정, 결과 CSV/Markdown 생성).

---
## 6. 디스패치 최적화 엔진 (핵심)

### 6.1 문제 정의

웨이브 하나에 대해, 캠프 `c`의 차량 집합 `V`와 후보 주문 집합 `O`가 주어졌을 때, 다음을 만족하는 라우트 집합 `R`을 찾는다.

- 각 주문은 최대 하나의 라우트에 배정된다(미배정 허용, 페널티).
- 라우트의 적재 중량·부피 ≤ 차량 용량 (하드).
- 냉장·위험물 등 속성 매칭 (하드).
- 기사 근무시간 안에 출발·복귀 (하드).
- 목적: **총비용 최소화**

```
cost(R) = Σ_r [ fixed(v_r) + dist_km(r)·perKm(v_r) + dur_min(r)·perMin(v_r) + Σ_stop late_min·penaltyPerMin ]
        + Σ_{o ∉ R} unassignedPenalty(o)
        + Σ_soft_rule_penalties
```

이는 시간창이 있는 용량 제약 차량 경로 문제(CVRPTW)의 변형이며 NP-hard이므로 **휴리스틱 + 시간 예산 내 개선**으로 푼다. "최적"의 의미는 *주어진 시간 예산 안에서 베이스라인 대비 검증된 개선*으로 정의하고, 벤치마크로 증명한다(§6.9).

### 6.2 입력 / 출력 모델 (도메인, Spring 비의존)

```java
record PlanningProblem(WaveRef wave, CampDepot depot, List<Candidate> candidates,
                       List<VehicleSpec> vehicles, RuleSet rules, CostModel cost, DistanceProvider distance) {}
record Candidate(OrderId id, GeoPoint point, Parcel parcel, TimeWindow promised, int priority) {}
record VehicleSpec(VehicleId id, Capacity capacity, VehicleAttrs attrs, ShiftWindow shift, VehicleCost cost) {}
record PlanResult(List<PlannedRoute> routes, List<Unassigned> unassigned, long totalCostKrw,
                  PlanMetrics metrics, List<Explanation> explanations) {}
record PlannedRoute(VehicleId vehicle, List<PlannedStop> stops, int distanceM, int durationS, long costKrw) {}
```

`DistanceProvider`는 `(GeoPoint a, GeoPoint b) → (meters, seconds)`를 반환. 기본 구현 `HaversineDistance`(도로계수 1.3, 평균 속도 25 km/h, 캠프 설정값). 선택 구현 `OsrmDistance`(테이블 API, 캐시). 문제 생성 시 거리 행렬은 **stop 통합 후** 계산해 `O(n²)` 규모를 줄인다(§6.7).

### 6.3 룰 엔진

**설계 원칙**: 룰은 데이터(DB)로 정의하고 타입별 평가기는 코드로 제공한다. 룰은 **하드**(위반 시 배정 불가)와 **소프트**(비용 가산)로 나뉜다. 모든 평가 결과는 `Explanation`으로 남겨 운영자가 "왜 이 주문이 미배정인지 / 왜 이 차량인지"를 볼 수 있다.

```java
sealed interface DispatchRule permits HardRule, SoftRule {
  String name(); int priority();
}
interface HardRule extends DispatchRule { Feasibility check(Candidate o, VehicleSpec v, RouteState r); }
interface SoftRule extends DispatchRule { long penaltyKrw(Candidate o, VehicleSpec v, RouteState r); }
```

**룰 카탈로그 (초기 구현 범위)**

| 타입 | 심각도 | 파라미터 | 의미 |
|---|---|---|---|
| VEHICLE_ATTRIBUTE_MATCH | HARD | orderFlag, vehicleFlag | 냉장 주문 → 냉장 차량, 위험물 → 허용 차량 |
| VEHICLE_CAPACITY | HARD | (없음, 차량 스펙 사용) | 중량·부피 누적 ≤ 용량 |
| MAX_STOPS_PER_ROUTE | HARD | max | 라우트당 최대 stop 수 |
| SHIFT_WINDOW | HARD | bufferMinutes | 복귀 시각 ≤ 근무 종료 − 버퍼 |
| TIME_WINDOW_LIMIT | HARD | hardLimitMinutes | 약속창 초과가 N분 이상이면 배정 불가 |
| TIME_WINDOW_PENALTY | SOFT | penaltyPerMinuteKrw | 약속창 초과 분당 페널티 |
| ZONE_AFFINITY | SOFT | crossZonePenaltyKrw | 라우트가 여러 권역에 걸치면 페널티 |
| PRIORITY_BOOST | SOFT | bonusKrw | 우선 고객(priority>0)을 앞 순서에 두면 보너스(음의 페널티) |
| VEHICLE_PREFERENCE | SOFT | preferredTypes, penaltyKrw | 소형 물량에 대형 차량 배정 시 페널티 |
| UNASSIGNED_PENALTY | SOFT | baseKrw, perPriorityKrw | 미배정 비용 (티어별 차등) |

**룰 정의 예시 (`dispatch_rules.params`)**

```json
[
  {"name":"cold-chain","type":"VEHICLE_ATTRIBUTE_MATCH","severity":"HARD","priority":10,"params":{"orderFlag":"requiresCold","vehicleFlag":"isCold"}},
  {"name":"max-stops","type":"MAX_STOPS_PER_ROUTE","severity":"HARD","priority":20,"params":{"max":120}},
  {"name":"late-hard-limit","type":"TIME_WINDOW_LIMIT","severity":"HARD","priority":30,"params":{"hardLimitMinutes":60}},
  {"name":"late-penalty","type":"TIME_WINDOW_PENALTY","severity":"SOFT","priority":100,"params":{"penaltyPerMinuteKrw":50}},
  {"name":"zone-affinity","type":"ZONE_AFFINITY","severity":"SOFT","priority":110,"params":{"crossZonePenaltyKrw":2000}},
  {"name":"unassigned","type":"UNASSIGNED_PENALTY","severity":"SOFT","priority":900,"params":{"baseKrw":30000,"perPriorityKrw":20000}}
]
```

**평가 순서**: 하드 룰을 priority 오름차순으로 평가하고 첫 위반에서 중단(사유 기록). 소프트 룰은 모두 평가해 합산. 룰셋은 캠프별 오버라이드(camp_id NOT NULL)가 전역(camp_id NULL)을 덮어쓴다. 룰 변경은 `rule_version` 증가 → 다음 계획부터 적용(진행 중 계획은 시작 시점 스냅샷 사용).

**설명(Explanation) 형식**

```json
{"orderId":"…","outcome":"UNASSIGNED","ruleName":"cold-chain","detail":{"reason":"no cold vehicle with remaining capacity","triedVehicles":3}}
{"orderId":"…","outcome":"ASSIGNED","routeId":"…","detail":{"marginalCostKrw":1840,"altVehicle":"V-07","altCostKrw":2210}}
```

### 6.4 비용 모델

`CostModel`은 차량 비용(고정·km·분)과 룰 페널티를 합산하는 순수 함수. 파라미터는 `dispatch_rules`와 `vehicles`에서 오며 코드에 상수를 두지 않는다. 단위는 KRW 정수(부동소수 금지).

### 6.5 알고리즘 파이프라인

```
plan(problem, budget):
  1. 전처리
     - 같은 geohash7 + 같은 약속창인 후보를 하나의 Stop으로 통합 (중량·부피 합산)
     - 하드 룰 사전 필터: (stop, vehicleType) 실행 가능 행렬 계산
     - Stop 간 거리 행렬 계산 (DistanceProvider, 통합 후)
  2. 클러스터링 (Sweep)
     - 캠프 기준 극각(angle)으로 정렬 → 용량·max-stops 한도 내에서 연속 구간을 클러스터로 자름
     - 권역(zone) 경계를 넘을 때는 ZONE_AFFINITY 페널티를 고려해 자르기 우선
  3. 차량 할당 (Greedy, 최소 한계비용)
     - 클러스터를 가장 이른 promised_end 순으로 정렬
     - 각 클러스터에 대해 실행 가능한 차량 중 marginalCost 최소 차량 선택
     - 실행 가능 차량이 없으면 클러스터를 분할(절반)해 재시도, 그래도 없으면 미배정 + 설명
  4. 시퀀싱 (Nearest Neighbor with Time Windows)
     - 캠프에서 출발, 다음 stop = (거리 + 대기시간 + 지각페널티)가 최소인 stop
  5. 개선 (Local Search, 시간 예산 내)
     - 2-opt (라우트 내 구간 뒤집기), Or-opt (1~3 stop 묶음 이동), inter-route relocate/swap
     - 개선 폭 < 0.1% 또는 예산 소진 시 종료
  6. 검증·산출
     - 모든 하드 룰을 최종 라우트에 재검증 (개선 단계 버그 방어선)
     - 후보 상태 재조회 — 계획 중에 취소된 주문을 stop 에서 뺀다 (§6.10, ADR-026).
       optimizer 밖의 일이다: 순수 함수는 스냅샷만 보고, 이 조회는 발행 어댑터가 한다
     - PlanResult(라우트, 미배정, 비용, 메트릭, 설명)
```

각 단계는 별도 클래스(`StopMerger`, `SweepClusterer`, `GreedyAssigner`, `NearestNeighborSequencer`, `LocalSearchImprover`, `PlanValidator`)로 분리해 단위 테스트와 교체가 가능하다.

### 6.6 전략 플러그인 구조

```java
public interface DispatchStrategy {
  String name();
  PlanResult plan(PlanningProblem problem, PlanningBudget budget);
}
```

| 전략 | 구성 | 용도 |
|---|---|---|
| `baseline-nn` | 클러스터링 없이 NN만 | 벤치마크 기준선 |
| `sweep-greedy-nn` | §6.5의 1~4단계 | fast mode 기본 |
| `sweep-greedy-nn+ls` | §6.5 전체 (2-opt/Or-opt/relocate) | **기본 전략** |
| `savings-cw+ls` | Clarke-Wright savings로 라우트 구성 후 LS | 비교 전략 |
| `timefold` | Timefold Solver(Community) VRPTW 모델 | 선택(Phase 4 stretch), ADR-004 비교용 |

전략은 `PlanRunner`가 `strategy` 파라미터로 선택하며 기본값은 설정 `dawnline.dispatch.default-strategy`. 새 전략 추가는 인터페이스 구현 + 등록만으로 가능해야 한다.

### 6.7 성능 목표, 병렬화, 시간 예산, 열화 모드

| 항목 | 목표 (8코어 노트북, Docker Compose) | 측정 방법 |
|---|---|---|
| 웨이브 5,000 주문 / 40 차량 계획 시간 | p95 ≤ 30초 (기본 전략) | `dawnline_plan_duration_seconds{strategy}` |
| 같은 조건 fast mode | ≤ 5초 | 동일 |
| 메모리 | 계획 1회 힙 증가 ≤ 1 GB | JFR/actuator |
| 베이스라인 대비 총비용 | ≥ 15% 절감 | benchmark 리포트 |
| 미배정률 (정상 용량) | ≤ 0.5% | plan 메트릭 |

- **병렬화**: 클러스터별 시퀀싱·개선은 독립이므로 `ForkJoinPool`(CPU 바운드)로 병렬 실행. Kafka 리스너·DB I/O는 가상 스레드. 캠프 간 계획은 파티션(campId)별로 자연 병렬.
- **시간 예산**: `PlanningBudget(totalMs, perRouteMs)`. 기본 30초. 개선 단계는 잔여 예산을 클러스터 수로 나눠 배분.
- **열화(degrade) 모드**: `wave.closed` 소비 지연(consumer lag) > 3 웨이브 또는 직전 계획이 예산의 80% 초과 → 다음 계획은 자동 `mode=FAST`(개선 단계 생략). 메트릭·로그로 노출, 운영자가 수동 재계획 가능. 이것이 "성수기에도 정시"를 위한 명시적 트레이드오프다.
- **거리 행렬**: stop 통합 후 n≈3,000이면 900만 쌍. 하버사인은 즉시 계산 가능하나 OSRM 사용 시 캐시 필수(`dist:{gh7a}:{gh7b}` Redis, TTL 1일).

### 6.8 재계획 (Partial Re-plan)

트리거: `delivery.at-risk`(지연 위험) 또는 운영자 커맨드.
1. 해당 라우트의 미완료 stop만 문제로 재구성 (완료·진행 중 stop 고정).
2. 같은 캠프에서 여유 용량이 있는 **진행 중 라우트**(현재 위치 기준)와 **미출발 차량**을 후보 차량으로 구성.
3. 동일 파이프라인으로 계획하되 `relocate`만 허용(대규모 재편 금지, 기사 혼란 방지).
4. 결과는 `route.assigned.v1`에 `revision` 증가로 발행. tracking·ops는 revision이 낮은 이벤트를 무시(멱등).
   **`plan.completed` 는 다시 내지 않는다** — 그 이벤트의 의미는 「최초 전체 계획의 완료」이고,
   재계획마다 다시 내면 웨이브 상태와 ops 화면이 무엇을 세는지 모르게 된다([ADR-024](adr/ADR-024-plan-completed-event.md)).
5. 재계획도 라우트당 10분 쿨다운.

**트리거는 위 둘뿐이다.** 취소는 여기에 들어가지 않는다 — 취소는 최적화의 트리거가 아니라 입력
변경이고, 다시 풀 가치가 있는지는 revision 을 받은 tracking 의 ETA 재계산이 정한다
([ADR-026](adr/ADR-026-dispatch-cancellation-window.md) 결정 1). 트리거를 늘리면 같은 판단을 하는
회로가 둘이 되고, 둘은 갈라진다.

### 6.9 벤치마크 방법

- 데이터셋: `tools/benchmark/datasets/` — `small`(500 주문/5 차량), `medium`(2,000/20), `large`(5,000/40), `peak`(15,000/60), 각각 seed 고정 생성. 좌표는 서울 근사 격자(캠프 중심 반경 8 km, 밀도 불균일).
- 지표: 총비용, 총거리, 계획 시간, 미배정 수, 지각 stop 수·평균 지각분, 차량 사용 대수.
- 각 전략 × 데이터셋을 5회 반복, 중앙값·p95 기록. 결과는 `docs/benchmarks/YYYY-MM-DD.md`에 표와 함께 커밋. README 상단에 최신 표를 링크.
- 회귀 방지: CI에서 `small`을 1회 실행해 기본 전략 비용이 베이스라인보다 나쁘면 실패.

### 6.10 취소 처리 (`order.cancelled` 소비)

[ADR-017 후속 정정](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md)이 §5.1 에
정의한 경합 창 — 계획 발행부터 order-service 가 `order.dispatched` 를 소비하기까지 취소가 정상적으로
성공하는 구간 — 을 **닫는 쪽이 여기다**([ADR-026](adr/ADR-026-dispatch-cancellation-window.md)).

분기는 라우트의 출발 여부가 아니라 **stop 의 상태**로 자른다. 미출발과 출발 후 미도착은 처리가
같아서(건너뛴다) 구분이 아무것도 만들지 않는다.

| 취소 도착 시 상태 | 처리 | 이벤트 |
|---|---|---|
| 후보, 계획 전 | `dispatch_candidates.status = CANCELLED`. **삭제하지 않는다** — "주문 X 는 왜 라우트에 없나" 에 답해야 한다(§6.3 설명 가능성) | 없음 |
| 후보, **계획 진행 중** | 계획은 시작 시점 스냅샷으로 돌고, **발행 직전 재검증(§6.5 6단계)** 이 후보 상태를 다시 읽어 `CANCELLED` 를 stop 에서 뺀 뒤 발행한다 | 없음 (revision 을 쓰지 않고 닫는 자리) |
| 라우트 발행됨, stop 이 `ARRIVED` **이전** | `route_stops.status = CANCELLED` + 이후 stop 시간 **재전파**(순서 불변) | `route.assigned` revision + 1 |
| stop 이 `ARRIVED`/`COMPLETED` **이후** | **거부.** 상태 불변 | 없음. `dawnline_cancel_too_late_total{camp}` (§9.4 알림) |

시간만 당기고 **순서는 재시퀀싱하지 않는다.** 기사가 이미 그 순서를 보고 있기 때문이고, §6.8 이
`relocate` 만 허용하는 것과 같은 종류의 판단이다.

취소된 stop 은 `route.assigned` 페이로드에서 **지우지 않고** `status: CANCELLED` 로 남긴다 —
부재는 값이 아니다. 지우면 소비자가 "취소" 와 "다른 라우트로 이동" 과 "발행 누락" 을 구별할 수
없고, tracking 은 이 이벤트만으로 shipment 를 만들어야 하므로(불변규칙 4) 그 구별이 그쪽의 유일한
정보원이다. `seq` 도 그대로 둔다.

네 번째 행이 발화한다는 것은 order-service 가 `order.dispatched` 를 배송 완료 시점까지 소비하지
못했다는 뜻이다(정상이면 발행과 출발 사이가 분 단위 이상). 그래서 그 카운터는 이상이 아니라
**창의 폭**을 재는 값이고, 물리적으로는 배송됐는데 주문은 `CANCELLED` 인 상태를 ops 가 보게 하는
것이 이 분기의 역할이다. 자동 보상은 넣지 않는다 — 환불·회수는 이 프로젝트의 범위 밖이다.

---

## 7. 데이터 저장소 설계

### 7.1 PostgreSQL

- **DB-per-service**: Compose에서는 PostgreSQL 인스턴스 1개에 서비스별 데이터베이스(`dawnline_order` 등) 분리. 접속 계정도 분리해 교차 접근을 물리적으로 차단.
- 마이그레이션: Flyway, `V<n>__<desc>.sql` (서비스별). JPA `ddl-auto`는 `validate`만 허용.
- ID: UUIDv7 (애플리케이션 생성, 시간순 → 인덱스 지역성). PostgreSQL 18의 `uuidv7()`은 사용하지 않는다(ID를 DB 왕복 전에 알아야 outbox·이벤트에 쓸 수 있음).
- 인덱스는 위 DDL 명시분 외에 추가 금지(추가 시 EXPLAIN 근거를 PR에 첨부). **넣지 않기로 한 판단도 행 수와 함께 남긴다** — 예: `waves` 는 90일치가 4,000행 남짓이라 정리 배치가 순차 스캔으로 충분하다([ADR-023](adr/ADR-023-fulfillment-retention.md)). 그 문장이 있어야 규모가 바뀌었을 때 재검토 지점이 생긴다.
- 파티셔닝: `shipment_events`(일 단위), `outbox_events`는 발행 후 7일 지난 행을 배치 삭제(파티션 대신 삭제, 규모가 작음). `processed_events` 는 14일 보존(§4.4) — 같은 정리 스케줄러가 일 1회 처리한다. 두 삭제 모두 `LIMIT` 배치를 반복해 긴 락을 잡지 않는다.
- **FK 대상 컬럼은 전체 인덱스로 만든다. 부분 인덱스는 참조 무결성(RI) 검사에 쓰이지 않는다** — 플래너가 부분 인덱스의 술어로 RI 검사(모든 상태)를 덮을 수 있음을 증명하지 못하기 때문이다. 부모 행을 지울 때마다 자식 테이블 전수 스캔이 된다.
- **부분 인덱스는 걸러내는 비율이 클 때만 쓴다.** 2% 를 거르려고 술어를 다는 것은 크기를 거의 줄이지 못하면서 위 RI 경로에서는 *인덱스가 없는 것과 같은 결과*를 낳을 수 있다. 두 규칙 모두 [측정](benchmarks/phase2-fulfillment-orders-indexes.md) §3 에서 나왔다([ADR-022](adr/ADR-022-fulfillment-order-aggregate.md) 후속 정정).
- `fulfillment_orders` 의 두 인덱스는 [EXPLAIN 근거](benchmarks/phase2-fulfillment-orders-indexes.md)를 갖는다. `wave_id` 는 **부분 인덱스가 아니다** — 부분 조건이 거르는 행이 2% 뿐이고(정상 상태의 98% 가 `PLANNED`), 무엇보다 부분 인덱스는 FK 검사에 쓰이지 못해 `waves` 삭제가 웨이브당 전수 스캔이 된다([ADR-022](adr/ADR-022-fulfillment-order-aggregate.md) 후속 정정).
- 보존 정책 한눈에: `outbox_events` 7일 · `processed_events` 14일(§4.4) · `idempotency_keys` 7일([ADR-019](adr/ADR-019-idempotency-record-retention-7-days.md)) · **`fulfillment_orders` 30일 · `waves` 90일**([ADR-023](adr/ADR-023-fulfillment-retention.md)) · `shipment_events` 30일(§5.4). `fulfillment_orders` 는 **파티셔닝하지 않는다** — 파티션 키가 PK 에 들어가면 [ADR-022](adr/ADR-022-fulfillment-order-aggregate.md) 가 확보한 `order_id` 단독 PK 보장이 약해진다.
- 낙관적 락(`version`)은 상태 전이가 있는 모든 애그리거트에 적용. 비관적 락은 `waves` 행 두 자리뿐이고 둘 다 짧은 트랜잭션이다 — **편입은 `SELECT … FOR SHARE`, 마감은 `SELECT … FOR UPDATE`**([ADR-025](adr/ADR-025-wave-admission-share-lock.md)). 편입에 배타 락을 쓰면 §8.2 피크에서 웨이브 행 하나가 처리량 상한이 된다. 공유 락끼리는 막지 않고, 마감의 배타 락이 진행 중인 편입을 기다렸다가 `CLOSING` 으로 바꾸므로 "마감된 웨이브에 주문이 새는" 창도 함께 닫힌다.
- N+1 방지: 컬렉션 로딩은 `@EntityGraph` 또는 명시 fetch join. 테스트에서 Hibernate statement 카운터로 쿼리 수 상한 검증.

### 7.2 Redis 사용 카탈로그

| 키 패턴 | 자료구조 | 서비스 | TTL | 장애 시 폴백 |
|---|---|---|---|---|
| `idem:order:{key}` | STRING | order | 24h | DB `idempotency_keys`만으로 동작 |
| `rl:customer:{id}` | HASH(Lua 토큰버킷) | order | 60s | **허용**(fail-open) + `bypassed` 메트릭·알림 |
| `geo:fc`, `geo:camp` | GEO | fulfillment | 없음(기동 시 재적재) | DB 전체 조회 후 메모리 하버사인 |
| `zone:geohash5:{p}` | STRING(`zoneId:campId`) | fulfillment | 10m | DB 조회 |
| `lock:wave:{id}`, `lock:plan:{waveId}` | STRING NX | fulfillment, dispatch | 60s | 단일 인스턴스 가정 하 DB 낙관적 락으로 중복 방지 유지 |
| `rules:camp:{id}:v{n}` | STRING(JSON) | dispatch | 1h | DB 조회 |
| `dist:{gh7a}:{gh7b}` | STRING | dispatch(OSRM 시) | 1d | 하버사인 |
| `route:{id}:progress` | HASH | dispatch/tracking | 2d | DB 조회 |
| `driver:{id}:pos` | GEO | tracking | 1h | 없음(시각화용) |
| `route:{id}:atrisk:cooldown` | STRING NX | tracking | 5m | 중복 at-risk 허용(멱등 소비자가 흡수) |

원칙: Redis는 **성능·조정(coordination)** 용도이며 **유일한 진실 저장소가 아니다**. 어떤 키가 사라져도 정확성은 DB로 회복된다.

**레이트 리밋 버킷의 의미** (`rl:customer:{id}`): 용량 60, 초당 1개 리필. 정확히 "분당 60회" 가 아니라
**분당 60을 넘는 지속 부하를 막되 짧은 버스트는 허용**한다는 뜻이다. 오래 쉰 고객은 가득 찬 버킷으로
시작해 60회를 연속으로 쓸 수 있고, 그 뒤에는 초당 1회 속도로만 이어갈 수 있다. 멱등 재요청도 센다 —
구분하면 복잡도만 늘고, 이 속도에서 재시도 몇 번은 문제가 되지 않는다. 자료구조가 HASH 인 이유는
토큰 수와 마지막 갱신 시각 두 값을 원자적으로 읽고 써야 하기 때문이다.

**fail-open 은 반드시 관측된다.** 인증이 없는 API(§10)에서 레이트 리밋은 <strong>유일한 남용
방지 수단</strong>이다. Redis 장애로 그것이 조용히 사라지면 보상 통제가 사라진 채로 서비스가
계속 도는 것이므로, 건너뛴 판정은 `dawnline_rate_limit_decisions_total{outcome=bypassed}` 로
세고 §9.4 알림에 넣는다.

**GEO 적재는 다른 예산을 쓴다** (`dawnline.fulfillment.redis.load-command-timeout`, 기본 2초).
50 ms 는 `order.placed` 소비 중의 `GEOSEARCH`·권역 캐시 조회를 위한 **핫패스** 값이고, 그 자리에는
폴백이 있다. 적재는 핫패스가 아니며 그 자리에는 폴백이 없다(적재 실패는 *이후 조회*가 폴백을
타게 할 뿐이다). 핫패스 예산을 적재에 쓰면 첫 명령에 연결 수립이 포함되는 느린 환경에서 매번
첫 시도가 실패하고, 재시도가 있어 동작은 하지만 **그 실패 로그가 진짜 장애를 가린다.**
로더는 이 예산을 가진 전용 연결로 돈다.

**Redis 명령 타임아웃은 짧다**(`dawnline.order.redis.command-timeout-ms`, 기본 50ms).
order-service 의 Redis 사용은 <em>전부</em> 실패해도 안전한 최적화이고(멱등은 DB 폴백, 레이트
리밋은 허용), 둘 다 `POST /orders` 핫패스에 있다. 기본 명령 타임아웃(60초)을 그대로 두면
Redis 가 <em>멈췄을 때</em> 폴백이 아니라 SLO 파괴가 된다 — 응답을 60초 기다린 뒤 "허용" 하는 것은
허용이 아니다. 여기에 더해 실패가 감지되면 `dawnline.order.redis.outage-bypass-ms`(기본 10초)
동안 Redis 호출 자체를 건너뛴다.

### 7.3 Kafka 토픽 설정 (로컬)

파티션 12, replication 1(로컬), `retention.ms` 7일, DLQ 30일. 프로덕션 확장 시 파티션 = 캠프 수 × 2 이상, replication 3, `min.insync.replicas=2`, 프로듀서 `acks=all`, `enable.idempotence=true`.

---

## 8. 신뢰성과 피크 대응

### 8.1 SLO (데모 환경 기준, 실측으로 갱신)

| SLI | 목표 |
|---|---|
| `POST /orders` p99 지연 | ≤ 200 ms (500 rps 지속 시) |
| `POST /orders` 가용성 | ≥ 99.9% (5xx 비율) |
| 주문 접수 → 디스패치 후보 적재 (E2E) | p95 ≤ 5초 |
| 웨이브 계획 시간 (5,000 주문) | p95 ≤ 30초 |
| 정시 배송률 — **원 약속 기준** (시뮬레이션, 지연 주입 5%) | ≥ 97% |
| 정시 배송률 — 개정 약속 기준 | 참고값 (목표 없음) |
| Outbox 지연 | p95 ≤ 2초 |

**정시율을 두 값으로 내는 이유**: 하류가 약속을 지키지 못해 창을 개정하면(§5.2 `promiseRevised`),
개정된 창 기준으로는 여전히 "정시" 다. 그 값 하나만 내면 **개정으로 정시율을 세탁할 수 있다** —
못 지킬 것 같으면 약속을 미루면 되기 때문이다. SLO 는 <strong>고객이 처음 받은 약속</strong>을
기준으로 잰다. 개정 기준 값은 "개정 이후에는 잘 지켰는가" 를 보는 참고값이고, 그 둘의 차이가
곧 `dawnline_promise_revised_total` 이 세는 사건의 크기다.

### 8.2 피크 시나리오 모델

- 평시: 캠프 10개 × 3,000 주문/일 = 30,000/일, 최대 50 rps.
- 피크(연말 세일): 5배 = 150,000/일, 컷오프 직전 1시간에 30% 집중 → 최대 ~600 rps 버스트.
- 완충 지점: (1) 주문 API는 외부 호출 없이 INSERT만 → 수평 확장으로 흡수 (2) Kafka가 하류 처리 지연을 흡수 (3) 웨이브가 배치 경계를 만들어 최적화 부하를 컷오프 시점으로 모음 (4) 열화 모드(§6.7).
- 확장 경로: order-service 인스턴스 N개(무상태), Kafka 파티션 ≥ 소비자 수, dispatch는 캠프 파티션 단위 병렬, PostgreSQL 커넥션 풀 총합 관리(HikariCP, 인스턴스당 10).

### 8.3 백프레셔

- Kafka 소비자: `max.poll.records=100`, 처리 중 `pause()`, 완료 후 `resume()`. 리스너 컨테이너 concurrency = 파티션 수 이하.
- dispatch 계획 큐: `wave.closed`는 캠프별 직렬이므로 큐 자체가 백프레셔. 연속 지연 감지 시 FAST 모드.
- 주문 API: 고객별 레이트 리밋(Phase 1) + 전역 `Bulkhead`(동시 요청 상한, 초과 시 429 + `Retry-After`) — **Bulkhead 는 Phase 7 이월**이며, Phase 1-9 의 k6 에서 HikariCP 풀(인스턴스당 10) 포화가 관측되면 Phase 1 안으로 당긴다(IMPLEMENTATION_PLAN).

**Bulkhead 판정 기록** — Phase 2 마감의 게이트다. 이 표가 채워지지 않으면 Phase 2 를 닫지 않는다.

| 항목 | 값 |
|---|---|
| 측정 커밋 · 일시 | `f7c860d` · 2026-09-05 |
| `POST /orders` p99 (500 rps × 60초) | **웜 4.8~48.0 ms** / **콜드 1,972~4,005 ms** · 목표 ≤ 200 ms |
| Outbox 지연 p95 | **웜 0.09~0.12초** / 콜드 1.61초 · 목표 ≤ 2초 ✅ |
| `hikaricp_connections_pending` 최댓값 | **웜 0** / **콜드 191** (풀 상한 10, `active` 는 10 에 붙음) |
| 판정 | **Phase 7 유지.** 조건(`pending` > 0)은 콜드에서 켜졌으나, 원인이 풀 분리로 완화되는 종류가 아니다 — 0.75 CPU 에서 SerialGC 가 선택되어 full GC 166회·17.11초가 발생했고, 그 때문에 요청이 커넥션을 3.07초까지 쥐었다. 웜에서는 같은 풀 10 개가 500 rps 를 `pending=0` 으로 처리한다. **대신 「콜드 스타트」를 Phase 7 항목으로 새로 연다** |

> **이 판정은 미리 적어 둔 「다음 행동」과 다르다.** 판정표(벤치마크 문서 4절)는 "포화가
> 관측되면 당긴다" 였고 포화는 관측됐다. 벗어난 이유는 증거가 그 조건문의 <em>전제</em>를
> 부정했기 때문이며(포화가 풀 분리로 완화되지 않는다), **벗어났다는 사실과 함께** 남긴다.
> 되돌리려면 위 표의 판정 칸만 바꾸면 된다.

원자료는 `docs/benchmarks/phase1-orders-k6.md` 3·5·6절이고 여기에는 결론만 옮긴다.
**두 번 요청되고도 오지 않은 항목은 기억이 아니라 게이트로 처리한다** — 이 프로젝트에서 레이트
리밋이 그렇게 빠질 뻔했다.
- 모든 소비자 랙은 `kafka_consumer_lag`로 노출, 임계 초과 알림.

### 8.4 장애 모드 표

| 컴포넌트 장애 | 증상 | 자동 대응 | 수동 대응 (런북) |
|---|---|---|---|
| Kafka 브로커 다운 | outbox 미발행 누적 | 릴레이 재시도, 주문 API 정상 | RB-01: 브로커 복구 후 outbox 지연 해소 확인 |
| PostgreSQL 다운(서비스 1개) | 해당 서비스 5xx, 레디니스 실패 | 트래픽 차단(프로브), 소비자 재시도 후 pause | RB-02 |
| Redis 다운 | 성능 저하, 락 폴백 | 폴백 경로(§7.2) | RB-03: 복구 후 geo 재적재 확인 |
| dispatch 계획 중 크래시 | plan `PLANNING` 정체 | 10분 후 자동 재실행 | RB-04: 강제 재실행 |
| 독약 메시지 (소비 측) | 소비자 반복 실패 | 3회 후 DLQ | RB-05: 원인 수정 후 replay |
| 독약 행 (발행 측) | 릴레이가 봉투 조립 실패 반복 | 결정적 실패로 분류해 격리(`failed_at`), 뒤 행은 계속 발행 (§4.6, ADR-015) | RB-05: 원인 수정 후 `failed_at = NULL` 로 재큐 |
| 컷오프 스케줄러 이중 실행 | 없음 | Redis 락 + 낙관적 락 | — |
| 시뮬레이터 폭주 | 429 증가 | 레이트 리밋 | — |

### 8.5 멱등성 지점 목록

| 지점 | 키 | 저장소 |
|---|---|---|
| POST /orders | Idempotency-Key | Redis + DB |
| 모든 Kafka 리스너 | eventId + consumer | DB processed_events |
| 웨이브 생성 | (camp, tier, cutoff) UNIQUE | DB |
| 계획 실행 | wave_id UNIQUE | DB |
| route.assigned 소비 | routeId + revision | DB (revision 비교) |
| 기사 스캔 이벤트 | (routeId, seq, type) + 상태 머신 | DB |
| at-risk 발행 | 라우트 쿨다운 | Redis (소실 시 중복 허용) |

### 8.6 기동·종료

- 레디니스: **DB 마이그레이션 완료만**. Kafka 브로커 연결은 넣지 않는다 — 브로커 장애 시에도 쓰기 경로는 outbox로 정상 동작해야 하기 때문이다(§8.4, [ADR-016](adr/ADR-016-readiness-excludes-kafka.md)). 브로커 상태는 레디니스가 아니라 outbox 지연·랙 알림으로 감시한다.
- **Redis GEO 적재도 넣지 않는다**(2026-09-05 정정, ADR-016 후속 정정). 이전 판은 "(fulfillment) GEO 적재 완료"를 조건으로 적었는데, 그것은 §7.2 가 `geo:fc`·`geo:camp` 에 폴백(DB 전체 조회 + 메모리 하버사인)을 둔 것과 모순이다. **폴백이 있는 의존성을 레디니스에 넣으면 Redis 장애가 곧 서비스 차단이 되어 폴백을 만든 이유가 사라진다.** 적재는 best-effort 로 하고 주기적으로 재시도하며, 상태는 `dawnline_geo_index_loaded{index}` 게이지(0/1)와 폴백 사용 카운터로 관측한다(§9.1) — 레이트 리밋의 `bypassed` 와 같은 방식이다.
- 그레이스풀 셧다운: HTTP 드레인 30초, Kafka 소비자 커밋 후 종료, 진행 중 계획은 `PLANNING` 유지(재실행 경로가 회수).

---

## 9. 관측성과 운영

### 9.1 커스텀 메트릭 (Micrometer)

**emit 주체를 적는 이유**: 라벨은 그것을 <strong>내보내는 서비스가 실제로 아는 값</strong>이어야 한다.
모르는 라벨을 표에 적어 두면 구현할 때 `unknown` 으로 채우거나(카디널리티만 늘고 쓸모없다) 다른
서비스의 데이터를 끌어오게 된다(불변규칙 3·4 위반). Phase 1 에서 `dawnline_orders_placed_total`
의 `camp` 가 정확히 그 경우였다 — 캠프는 접수 시점에 존재하지 않는다.

| 메트릭 | 타입 | emit 주체 | 라벨 |
|---|---|---|---|
| `dawnline_orders_placed_total` | counter | order | tier — **camp 는 없다**. 캠프는 fulfillment 가 정하므로(§5.2) 접수 시점에는 존재하지 않는다. 캠프별 유입은 `dawnline_wave_orders` 가 본다 |
| `dawnline_idempotent_replays_total` | counter | order | tier — 같은 멱등 키의 재요청으로 저장된 응답을 재생한 횟수. `orders_placed` 와 함께 보면 **클라이언트 재시도 폭주와 실제 주문 증가를 구분**할 수 있다 |
| `dawnline_rate_limit_decisions_total` | counter | order | outcome(allowed/limited/bypassed) — `bypassed` 는 Redis 장애로 판정을 건너뛴 것이다 (§7.2) |
| `dawnline_outbox_lag_seconds` | gauge | 전 서비스 | service |
| `dawnline_outbox_unpublished` | gauge | 전 서비스 | service |
| `dawnline_outbox_failed` | gauge | 전 서비스 | service — 격리된(미해결) outbox 행 수 (§4.6) |
| `dawnline_event_processed_total` | counter | 전 소비자 | consumer, eventType, outcome(ok/dup/rejected/dlq) |
| `dawnline_event_rejected_total` | counter | 전 소비자 | **consumer, eventType, reason** — 비즈니스 규칙 위반으로 무시한 이벤트 (§4.6). `outcome=rejected` 가 "몇 번" 을 세고 이쪽이 "왜" 를 센다. 예약해 둔 라벨 확장을 Phase 2-8 에서 붙였다 — 거부하는 소비자가 order·fulfillment 둘이 되어 "누가 무엇을" 이 필요해졌다. **세 라벨은 이 카운터를 올리는 모든 곳이 같이 써야 한다**(`IdempotentConsumer`·두 리스너): Prometheus 는 같은 이름의 미터가 같은 라벨 키 집합을 갖기를 요구하므로 한쪽만 붙이면 다른 쪽 등록이 실패한다 |
| `dawnline_event_stale_total` | counter | 전 소비자 | consumer, eventType — 이미 지나온 지점으로의 전이라 무시한 이벤트 (ADR-017) |
| `dawnline_wave_orders` | gauge | fulfillment | camp, tier — 마감 시점의 편입 주문 수. `waves.order_count` 는 마감 전 0 이므로([ADR-025](adr/ADR-025-wave-admission-share-lock.md)) 이 값이 편입량의 유일한 관측 경로다. 스크레이프마다 집계하지 않고 **마감할 때 이미 센 값**을 남긴다 — 관측이 §8.2 피크에 부하가 되면 안 된다 |
| `dawnline_fc_fallback_total` | counter | fulfillment | camp, reason(tier/cold/inventory) — 캠프의 홈 FC 가 §5.2 1~3단계 필터에서 떨어져 대체 FC 를 고른 횟수. 계속 오르는 캠프는 홈 FC 배정이 잘못됐거나 그 FC 의 역량이 부족한 것이다 |
| `dawnline_promise_revised_total` | counter | fulfillment | camp, tier — 하류가 상류의 약속을 개정한 횟수 (§5.2, Phase 2) |
| `dawnline_geo_index_loaded` | gauge | fulfillment | index(fc/camp) — Redis GEO 적재 성공 여부 0/1. **레디니스가 아니라 이 게이지가 GEO 상태를 말한다**(§8.6, ADR-016 후속 정정). 0 이어도 서비스는 폴백으로 정상 동작한다 |
| `dawnline_geo_lookups_total` | counter | fulfillment | index, outcome(redis/bypassed) — `bypassed` 는 Redis 를 건너뛰고 DB 전체 조회 + 메모리 하버사인으로 답한 것이다(§7.2). 레이트 리밋의 `bypassed` 와 같은 어휘를 쓴다 — **폴백은 조용히 일어나면 안 된다** |
| `dawnline_plan_duration_seconds` | histogram | dispatch | strategy, mode |
| `dawnline_plan_cost_krw` | gauge | dispatch | camp |
| `dawnline_plan_unassigned` | gauge | dispatch | camp |
| `dawnline_plan_degraded_total` | counter | dispatch | camp |
| `dawnline_cancel_too_late_total` | counter | dispatch | camp — 이미 `ARRIVED`/`COMPLETED` 인 stop 에 도착해 **거부한** `order.cancelled` (§6.10, [ADR-026](adr/ADR-026-dispatch-cancellation-window.md)). order-service 의 축 밖 거부 카운터와 **한 쌍**이다 — 저쪽은 "취소된 주문에 배차가 왔다", 이쪽은 "배송된 주문에 취소가 왔다" 를 세고 둘 다 같은 경합 창의 양 끝이다. 오르면 볼 곳은 dispatch 가 아니라 order-service 의 `order.dispatched` 컨슈머 랙이다 |
| `dawnline_at_risk_total` | counter | tracking | camp — campId 는 `route.assigned` 가 싣고 오지만(필수 필드) §5.4 의 `shipments` 에는 컬럼이 없다. **Phase 5 에서 보관해야 이 라벨을 붙일 수 있다** |
| `dawnline_delivery_on_time_ratio` | gauge | **ops-api** | camp, basis(promised/revised) — §8.1 참고. 두 값을 <em>따로</em> 낸다 |

Kafka 소비자 랙·프로듀서 지표는 Spring Kafka 기본 지표 사용.

**정시율을 tracking 이 아니라 ops-api 가 내는 이유**: `basis` 라벨은 <em>원래 약속</em>과
<em>개정된 약속</em> 두 기준을 모두 알아야 성립한다(§8.1). tracking 은 `route.assigned` 가 준
`promised_end` 하나만 갖고 있어 그것이 원래 것인지 개정된 것인지 구분하지 못한다. ops-api 는 모든
토픽을 구독하므로(§5.5) `order.placed` 의 원래 창과 `fulfillment.planned` 의 `promiseRevised` 를
함께 본다 — 그 둘을 아는 유일한 자리다. 이것을 tracking 에 두면 개정 여부를 알기 위해
fulfillment 의 데이터를 끌어와야 하고, 그것이 불변규칙 4가 막으려는 것이다.

**`reason` 을 `dawnline_event_processed_total` 의 라벨로 합치지 않는 이유**: Micrometer 의 Prometheus
레지스트리는 같은 이름의 미터가 서로 다른 태그 키 집합을 갖는 것을 거부한다(실제 메시지:
*"Prometheus requires that all meters with the same name have the same set of tag keys."*).
`reason` 을 붙이려면 `ok`·`dup`·`dlq` 에도 전부 붙여야 하고, 그러면 의미 없는 `reason="none"` 이
대부분을 차지한다. 그래서 "몇 번" 과 "왜" 를 두 카운터로 나눈다 (ADR-022).

`dawnline_event_stale_total` 과 `dawnline_event_processed_total{outcome=rejected}` 는 다른 것을 센다.
**stale** 은 순서 뒤바뀜이라 정상이고(ADR-017 — 사실은 이미 일어났고 순서가 다른 것은 우리가 알게 된
순서일 뿐이다), **rejected** 는 취소된 주문에 배송 이벤트가 오는 것처럼 사람이 봐야 하는 상황이다.
한 카운터로 합치면 알림을 걸 수 없다 — stale 은 늘 조금씩 늘고 rejected 는 0이어야 하기 때문이다.

### 9.2 트레이싱

OpenTelemetry(Micrometer Tracing → OTLP → Tempo). Kafka 헤더로 `traceparent` 전파. 하나의 주문 traceId로 order → fulfillment → dispatch(계획은 별도 span, waveId 태그) → tracking을 Grafana에서 한 줄로 볼 수 있어야 한다(데모 핵심).

### 9.3 로깅

JSON 구조 로그(traceId, spanId, service, eventId, orderId/waveId/routeId MDC). 개인정보(주소 전체)는 로그에 남기지 않는다(우편번호·geohash만).

### 9.4 대시보드·알림 (저장소에 JSON으로 커밋)

- `Order Intake`: rps, p99, 429/5xx, outbox 지연
- `Waves & Plans`: 웨이브별 주문 수, 계획 시간, 비용, 미배정, degraded
- `Delivery`: 정시율, at-risk, 실패, 라우트 진행
- `Platform`: consumer lag, DLQ 건수, DB 커넥션, JVM
- 알림 규칙: outbox 지연 > 30s, `dawnline_outbox_failed` > 0(격리 행 발생 — RB-05), DLQ 신규 > 0, consumer lag > 1,000, 계획 시간 p95 > 45s, 정시율 < 95%, **`dawnline_rate_limit_decisions_total{outcome="bypassed"}` 증가**(Redis 장애로 레이트 리밋이 꺼졌다 — 무인증 API 의 유일한 남용 방지 수단이 사라진 상태다, RB-03), **`dawnline_cancel_too_late_total` 증가**(배송이 끝난 주문에 취소가 도착했다 — 물리적 배송과 주문 상태가 어긋난 건이 생겼고 사람이 처리해야 한다. 자동 보상은 없다, §6.10)

### 9.5 런북 (`docs/runbooks/RB-0x.md`)

RB-01 Kafka 복구 · RB-02 DB 장애 · RB-03 Redis 복구 · RB-04 계획 정체/강제 재실행 · RB-05 DLQ 재처리·outbox 격리 재큐(§4.6) · RB-06 피크 대비 체크리스트(파티션·인스턴스·룰 파라미터 사전 점검).

---

## 10. 보안 (최소 범위)

- **고객 주문 API: 무인증 — 의도된 결정** (Phase 1 확정, §17 참조). 데모용 `X-Api-Key` 는 넣지 않는다.
  이 프로젝트가 증명하려는 것(멱등 처리, 상태 머신, 경로 최적화)에 API 키가 더하는 것이 없고,
  보안 역량은 아래 ops-api 의 JWT 가 담당한다. 나중에 붙이면 k6·sim-runner·통합 테스트를 전부
  소급 수정해야 하므로 "일단 미루기" 도 고르지 않았다.
  - 남용 방지는 레이트 리밋(`rl:customer:{id}`, §7.2)이 담당한다. **인증이 없으므로 그 키인
    `customerId` 는 클라이언트가 주장하는 값이다** — 다른 고객의 id 로 요청하면 그 고객의 버킷을
    소모시킬 수 있고, 자기 id 를 바꿔 가며 레이트 리밋을 우회할 수도 있다. 즉 현재의 레이트 리밋은
    악의적 공격이 아니라 폭주하는 클라이언트를 막는 장치다.
  - 실서비스 전환 시 인증 도입과 함께 재검토한다. 그때 레이트 리밋 키는 주장값이 아니라 인증된
    주체에서 와야 한다.
- ops-api: JWT + 역할. 시크릿은 환경변수. `.env` 커밋 금지.
- 입력 검증: Bean Validation, 주소·SKU 길이 제한, 좌표 범위.
- 의존성 취약점: GitHub Dependabot + `gradle dependencyCheck`(선택).
- 개인정보: 로그 마스킹(§9.3), 읽기 모델에는 주소 전체를 저장하지 않음.

---
## 11. 기술 스택 (2026-08-29 기준 안정 버전 확인)

| 계층 | 선택 | 버전 기준 | 선정 이유 / 비고 |
|---|---|---|---|
| 언어·런타임 | Java (Eclipse Temurin) | **25 LTS** (2025-09 GA, 현재 최신 LTS) | 가상 스레드·record·sealed·패턴 매칭 정식 활용. 26은 non-LTS라 제외 |
| 프레임워크 | Spring Boot | **4.1.x** (4.1.0: 2026-06-10, Spring Framework 7.0.x) | Spring Kafka 4.1, Spring Security 7.1, Hibernate ORM 7.x가 BOM으로 관리됨. 4.1이 신규 프로젝트 권장 라인 |
| 빌드 | Gradle (Kotlin DSL) | 9.x 최신 안정 wrapper | 멀티모듈 모노레포, 버전 카탈로그(`libs.versions.toml`) |
| 메시징 | Apache Kafka | **4.3.x** (KRaft, `apache/kafka` 이미지; 4.3.1: 2026-06) | ZooKeeper 없음. 클라이언트는 Spring Kafka BOM 버전 |
| RDB | PostgreSQL | **18.x** | 서비스별 DB. 파티셔닝·JSONB |
| 캐시/조정 | Redis | 8.x 최신 안정 이미지 | GEO·Lua·NX 락. `[결정 필요: 라이선스 이슈가 있으면 Valkey로 교체 — 명령 호환]` |
| ORM/마이그레이션 | Hibernate ORM (Boot BOM), Flyway | BOM 관리 | `ddl-auto=validate` |
| 문서 | springdoc-openapi | **3.1.0** (Boot 4 라인) — Phase 1 에서 동작 확인 | OpenAPI 3.1 자동 생성, `contracts/openapi/order-service.yaml` 로 내보내고 `OpenApiContractIT` 가 코드와의 일치를 검사 |
| 회복탄력성 | Resilience4j | **아직 쓰지 않는다.** `resilience4j-spring-boot4:2.4.0` 은 해결되지만 `resilience4j-spring6`(Spring Framework 6)을 끌고 온다 | Phase 3 의 OSRM 어댑터(Retry·CircuitBreaker)와 Phase 7 의 전역 `Bulkhead`(§8.3)에서 다시 판단한다. Phase 1 의 Redis 장애 차단기는 도입하지 않았다 — CircuitBreaker 가 자기 시계로 돌아 창 만료를 테스트하려면 실제로 기다려야 하고(불변규칙 12), 필요한 것은 `AtomicLong` 하나였다 |
| 관측성 | Micrometer + OpenTelemetry, Prometheus, Grafana, Tempo | 최신 안정 이미지 | Boot 4.1의 OTel 개선 활용 |
| 테스트 | JUnit(Boot BOM), Testcontainers, ArchUnit, WireMock(OSRM 스텁), k6 | 최신 안정 | §13 |
| 최적화(선택) | Timefold Solver Community | 최신 안정 | ADR-004 비교 실험용, 기본 경로 아님 |
| 프론트 | React 19 + Vite + TypeScript, Leaflet | 최신 안정 | ops-web 최소 범위 |
| 컨테이너 | Docker Compose; (선택) kind + Kubernetes 매니페스트 | 최신 안정 | 로컬 전체 스택 1명령 기동 |
| CI/CD | GitHub Actions, GHCR | — | §14 |

버전은 `gradle/libs.versions.toml`과 `deploy/compose/.env`에 **한 곳에서만** 고정한다. 마이너/패치 갱신은 Dependabot PR로 받는다.

---

## 12. 저장소 구조 (Gradle 멀티프로젝트 모노레포)

```
dawnline/
├── CLAUDE.md
├── README.md                         # 데모 GIF, 아키텍처 그림, 최신 벤치마크 표 링크
├── docs/
│   ├── DESIGN.md                     # 이 문서
│   ├── IMPLEMENTATION_PLAN.md
│   ├── adr/ADR-001-…md
│   ├── runbooks/RB-01-…md
│   ├── benchmarks/
│   └── postmortems/                  # 피크 시뮬레이션 가상 포스트모템 1건
├── contracts/
│   ├── events/*.schema.json, examples/*.json
│   ├── openapi/*.yaml                # 빌드 시 생성물 커밋
│   └── seed/*.txt                    # 생성물 커밋. 서비스 경계를 가로지르는 시드 전제 (ADR-021)
├── gradle/libs.versions.toml
├── settings.gradle.kts, build.gradle.kts, buildSrc/ (공통 컨벤션 플러그인)
├── libs/
│   ├── common/          # 값 객체(GeoPoint, Money, TimeWindow), UUIDv7, geohash, 에러 모델
│   ├── messaging/       # Envelope, Outbox(엔티티·릴레이·Flyway 스크립트), IdempotentConsumer, Kafka 설정
│   └── observability/   # 메트릭 명명, MDC 필터, 로그 설정
├── services/
│   ├── order-service/
│   ├── fulfillment-service/
│   ├── dispatch-service/   # domain/optimizer 패키지가 핵심 (Spring 비의존)
│   ├── tracking-service/
│   └── ops-api/
├── apps/ops-web/
├── tools/
│   ├── sim-runner/
│   └── benchmark/
├── deploy/
│   ├── compose/docker-compose.yml, .env.example, grafana/, prometheus/, tempo/
│   └── k8s/ (선택)
└── .github/workflows/ci.yml, release.yml
```

각 서비스 모듈은 `libs/*`만 의존한다. 서비스 간 소스 의존은 금지 — 현재 강제 수단은 ArchUnit 규칙 3(다른 서비스 *패키지를 참조*하면 실패)뿐이다. Gradle 수준의 가드(다른 `services:*` 를 의존에 추가하면 설정 시점에 실패)는 아직 없다.

---

## 13. 테스트 전략

| 계층 | 범위 | 도구 | 기준 |
|---|---|---|---|
| 단위 | domain, optimizer, 룰 평가기, 비용 모델 | JUnit, AssertJ | optimizer 패키지 라인 커버리지 ≥ 85%, 전체 ≥ 70% (JaCoCo 게이트) |
| 아키텍처 | 패키지 의존 방향, 서비스 간 참조 금지, JPA 어노테이션 위치 | ArchUnit | 위반 0 |
| 통합 | 리포지토리, Kafka 리스너, Outbox 릴레이, Redis 어댑터 | Testcontainers(PostgreSQL 18, Kafka 4.x, Redis 8) | 서비스별 최소 1개 E2E 유스케이스 |
| 계약 | 이벤트 스키마 검증, 예시 역직렬화 | JSON Schema validator | 발행자·소비자 양쪽 |
| 시스템 | Compose 전체 기동 후 주문 → 배송 완료 시나리오 | sim-runner `smoke` 시나리오 | CI에서 실행 |
| 성능 | 주문 API 부하, 계획 시간 | k6, benchmark 도구 | §8.1 목표 대비 리포트 |
| 카오스 | Kafka/Redis 중단·복구, 인스턴스 강제 종료 | Compose `stop/start` 스크립트 | 데이터 유실·중복 0 (검증 쿼리) |

**ArchUnit 규칙 목록**: (1) `domain`은 `org.springframework`, `jakarta.persistence` 의존 금지 (2) `application`은 `adapter` 의존 금지 (3) `com.dawnline.<svc>`는 다른 `<svc>` 패키지 참조 금지 (4) Kafka 리스너 클래스는 `adapter.in.messaging`에만 존재 (5) `@Transactional`은 `application` 계층에만 (6) `domain`·`application`은 `org.springframework.kafka` 의존 금지 — 발행은 Outbox 를 거친다(불변규칙 1) (7) 서비스 코드는 시스템 시계를 직접 읽지 않는다 — `Instant.now()`·`Clock.systemUTC()`·`Clock.systemDefaultZone()`·`now(ZoneId)`·`System.currentTimeMillis()` 금지(불변규칙 12).

규칙 7이 이름이 아니라 **인자 타입**으로 판정하는 이유: `LocalTime.now(Clock)` 은 주입받은 시계를 읽는 <em>올바른</em> 형태이고 `LocalTime.now(ZoneId)` 는 시스템 시계를 읽는 위반이다. 이름만 보면 둘이 같아 보인다 — 규칙을 처음 켰을 때 `TierEligibility.nowInServiceZone()` 이 그렇게 잘못 걸렸다. 분석 대상에서 테스트 클래스는 뺀다(`DoNotIncludeTests`): 규칙은 프로덕션 구조를 서술하는 것이고, "생성자가 잘못된 인자를 거부하는가" 를 보는 테스트는 버릴 객체를 만들려고 시스템 시계를 부를 수 있다.

규칙 6이 따로 필요한 이유: `libs/messaging` 이 Kafka 의존을 `api` 로 노출하므로 `KafkaTemplate` 이 5개 서비스 전부의 컴파일 클래스패스에 있다. 유스케이스가 그것을 직접 부르면 도메인 변경과 이벤트 발행이 서로 다른 트랜잭션이 되는데, 규칙 5는 어노테이션의 *위치*만 보므로 이를 잡지 못한다.

**규칙의 검증 상태**: 일곱 규칙 <strong>전부</strong> 위반 표본(`libs/common` 의 `archunit/samples/bad`)으로 "잡아야 할 것을 잡는지"까지 확인된다. Phase 0 마감 시점에는 규칙 3·4·5가 대상 0개라 미검증이었고, Phase 1에서 첫 `@Transactional`(규칙 5)·첫 `@KafkaListener`(규칙 4)·서비스 간 참조 표본(규칙 3)이 생기며 채워졌다.

규칙별 주의점 세 가지. (1) 규칙 1의 표본은 금지 대상 중 Spring 쪽만 건드린다 — `libs/common` 의 test 클래스패스에 `jakarta.persistence` 가 없기 때문이며, JPA 는 같은 `resideInAnyPackage` 술어에 들어가는 다른 패키지 문자열일 뿐 검사 경로가 다르지 않다. (2) 규칙 3의 표본은 `that` 절이 서비스 패키지로 좁혀져 있어 `com.dawnline.order`·`com.dawnline.fulfillment` 패키지에 두어야 한다. 그 클래스들은 `libs/common` 의 테스트 소스에만 있고 서비스의 테스트 클래스패스에는 없으므로 실제 분석에 섞이지 않는다. (3) 규칙 3·7은 <strong>반대 방향</strong>(통과해야 할 표본이 통과하는지)도 함께 본다 — 그 방향이 없으면 "모든 참조를 막는" 규칙이나 "시각을 아예 못 읽게 만드는" 규칙이 되어도 테스트가 통과한다.

**불변 규칙 ↔ 강제 수단 매핑** (CLAUDE.md 「아키텍처 불변 규칙」 13개 기준). ArchUnit이 닿는 것은 13개 중 6개(1·2·3·4·5·12)이고 그중 온전히 강제되는 것은 5·12번이다. 나머지는 API 설계·DB 권한·컴파일러·CI·리뷰가 맡는다 — 이 표는 "무엇이 자동으로 막히지 *않는지*"를 보이는 것이 목적이다.

| # | 불변 규칙 | ArchUnit | 그 밖의 강제 수단 | 음성 검증 |
|---|---|---|---|---|
| 1 | Outbox 필수 | 규칙 6(직접 발행 차단), 규칙 5(트랜잭션 경계 위치) | `OutboxAppender` 가 유일한 발행 API — `libs/messaging` 은 다른 발행 경로를 제공하지 않는다. 어노테이션이 <em>사라지는</em> 것은 ArchUnit이 못 잡으므로 `PlaceOrderTransactionTest` 가 그 존재를 직접 확인한다 | 규칙 6 ✅ / 규칙 5 ✅ |
| 2 | 멱등 소비자 필수 | 규칙 4 — 리스너의 *위치*만 제한. 멱등 체크를 했는지는 보지 못한다 | `IdempotentConsumer` API, PR 체크리스트, 리스너 IT 가 같은 이벤트를 두 번 보내 상태가 한 번만 바뀌는지 확인 | 규칙 4 ✅ / 멱등 체크 자체는 ✗ |
| 3 | 서비스 간 DB 접근 금지 | 규칙 3 — 소스 레벨 패키지 참조만 | DB 권한(`deploy/compose/initdb`): 서비스 DB·부트스트랩 DB 모두 `REVOKE CONNECT … FROM PUBLIC` | 규칙 3 ✅(양방향) / DB 권한 ✅(컨테이너에서 거부 확인) |
| 4 | 코어 서비스 간 동기 호출 금지 | 규칙 3이 부분 커버 — 모노레포 안의 패키지 참조만 잡는다. HTTP 클라이언트로 부르는 것은 못 잡는다 | PR 체크리스트, Compose 네트워크 구성 | 규칙 3 ✅ / HTTP 경로는 ✗ |
| 5 | domain 프레임워크 비의존 | 규칙 1 — 유일하게 온전히 강제된다 | — | ✅ |
| 6 | 상태 전이는 상태 머신 메서드로만 | — | 애그리거트에 세터를 두지 않는다, 코드 리뷰, **왕복 매핑 단위 테스트** | 부분 — `FulfillmentOrderEntityTest`·`WaveEntityTest` 가 도메인→행→도메인 왕복에서 필드가 사라지지 않는지 본다 |
| 7 | Redis는 진실 저장소가 아님 | — | §7.2 폴백 표, 카오스 시나리오(현재 `make chaos-kafka`), 어댑터가 `DataAccessException` 을 밖으로 내지 않는다 | ✅(멱등·GEO·권역) — `PlaceOrderIT`(order)와 `GeoFallbackIT`(fulfillment)가 죽은 Redis 주소로 컨텍스트를 띄워 각각 멱등과 FC 선택이 DB만으로 성립함을 보인다. **`GeoEquivalenceIT` 는 한 걸음 더 간다** — 폴백이 *동작하는가*가 아니라 시드 전체(캠프 10 × FC 3 × 티어 3 × 냉장 2)에서 Redis 와 **같은 답**을 내는가를 본다 |
| 8 | 이벤트 계약 우선 | — | 계약 테스트(`EventContractsTest` — 스키마·예시 양방향), `contracts/events/README` §3 | ✅ |
| 9 | 돈은 정수 KRW·좌표 `NUMERIC(9,6)`·시간 `TIMESTAMPTZ` | — | 컴파일러 — `Money` 는 `long` 을 감싸는 값 객체라 부동소수 금액이 타입에서 막힌다 | ✅(타입) |
| 10 | ID는 UUIDv7 | — | `Ids.newId()`, `IdsTest`(RFC 9562 비트 레이아웃·단조 증가) | ✅(생성기) |
| 11 | 인덱스 추가 금지(설계서 명시분 외) | — | PR 체크리스트(EXPLAIN 첨부), 마이그레이션 리뷰 | — |
| 12 | 시간·난수는 주입 | 규칙 7 — 시계 쪽은 온전히 강제된다. 난수(`RandomGenerator`)는 아직 아니다 | 생성자 시그니처, seed 재현성 테스트, `libs/messaging` 이 저장 정밀도로 자른 `Clock` 빈을 제공 | 규칙 7 ✅(양방향) |
| 13 | 머지된 마이그레이션 불변 | — | CI 「마이그레이션 불변 검사」 job — PR 에서 기존 `V*.sql` 이 수정·삭제·이동되면 실패 | ✅(CI) |

**손으로 옮기는 매핑은 단위 테스트가 잡는다.** 애그리거트와 엔티티를 분리하면(ADR-007) 필드를
양방향으로 옮기는 코드가 생기고, 거기서 **하나를 빠뜨리면 그 값은 예외 없이 조용히 사라진다.**
그것을 잡는 데는 DB 가 필요 없다 — 도메인→행→도메인 왕복이 손실 없는지만 보면 되고, 그래서
`FulfillmentOrderEntityTest`(16개 필드)·`WaveEntityTest` 는 단위 테스트다. DB 가 필요한 것은
<em>스키마가 엔티티와 맞는가</em>이고 그쪽은 `ddl-auto=validate` + 통합 테스트가 본다. 둘을 한
곳에서 하려 들면 느린 테스트가 느슨해진다.

**폴백 테스트는 전제를 스스로 말한다.** 의존성을 죽여 놓고 "그래도 된다" 를 보는 테스트는,
그 의존성이 <em>실제로 불가하다</em>는 것을 첫 어설션으로 확인한다. 확인이 없으면 전제가 무너진
날 테스트는 <strong>계속 통과하면서</strong> 아무것도 검사하지 않는 상태가 되고, 그것은 실패보다
나쁘다 — 실패는 보이지만 이쪽은 안 보인다. 이 저장소에서 세 번 있었고(마지막은 `GeoFallbackIT`
가 살아 있는 Redis 를 보고 통과한 일), 그래서 규칙이 되었다.

**결정론**: 최적화 테스트는 seed 고정. 시간은 `Clock` 주입으로 제어. Testcontainers 재사용(`testcontainers.reuse.enable=true`)으로 로컬 실행 시간 단축.

**시계는 하나다**(불변규칙 12). 도메인 전이가 `Clock` 에서 받은 시각으로 `updated_at` 을 옮기면,
어댑터에 두 번째 시계를 두지 않는다 — JPA `@PreUpdate`·`@UpdateTimestamp` 도, DB `DEFAULT now()`
도 쓰지 않는다. 시계가 둘이면 "그 주문에 마지막으로 무슨 일이 있었나" 의 답이 저장 시각으로
덮이고, 그것을 기준으로 도는 보존 정리([ADR-023](adr/ADR-023-fulfillment-retention.md))가 주입된
시계로는 재현되지 않는다.

---

## 14. CI/CD와 배포

**ci.yml (PR·main)**: checkout → JDK 25 → Gradle 캐시 → `./gradlew check`(단위+ArchUnit+계약+JaCoCo 게이트) → 통합 테스트(Testcontainers, Docker 서비스) → `benchmark small` 회귀 체크 → 이미지 빌드(Buildpacks, ADR-013) → Compose 스모크(주문 20건 E2E) → 결과 아티팩트(리포트, OpenAPI).

**release.yml (태그 `v*`)**: 이미지 GHCR 푸시(태그·`latest`), SBOM 생성, GitHub Release 노트.

**로컬 실행**: `make up`(전체 스택), `make demo`, `make peak`(피크 시나리오), `make down`. Makefile은 Compose 명령 래퍼다.

`make demo` 는 시드 확인 → 주문 200건(sim-runner smoke) → 웨이브 편입 → 컷오프 → `wave.closed` 까지를
**DB 와 브로커 양쪽에서** 확인하고 URL 을 출력한다(`tools/demo/phase2-demo.sh`). 두 곳을 다 보는 이유는
§4.3 과 같다 — outbox 에 행이 있는 것과 브로커에 레코드가 있는 것은 다른 사실이고, 그 사이에 릴레이와
봉투 조립이 있다. 확인하는 것은 캠프별 `wave.closed` 정확히 1회(이중 마감 없음), 파티션 키 = `campId`,
`orderCount` 가 마감 시 집계값과 일치(ADR-025), 그리고 **시드 부족으로 인한** `UNSERVICEABLE` 0건이다.
`OUT_OF_STOCK` 은 세지 않는다 — 시드가 §5.2 3단계를 보이려고 일부러 넣은 결손이라(ADR-021) 그 둘을 한
숫자로 합치면 "시드가 덜 됐다" 와 "시드가 의도대로 됐다" 가 구별되지 않는다.

컷오프는 기다리지 않고 **웨이브의 `cutoff_at` 을 과거로 민다**. §2.2 의 컷오프 표는 `libs/common` 의
`TierSchedule` 하나뿐이고(ADR-020 후속 정정 2), "데모용 짧은 컷오프 표" 를 만들면 그 ADR 이 없애려던
두 번째 복사본이 바로 그것이 된다. 표가 아니라 시각을 밀면 마감 판정·Redis 락·`FOR UPDATE`·outbox 는
운영과 같은 경로를 그대로 지난다 — 데모가 건드리는 것은 "언제" 뿐이다.

**배포 전략 문서(구현 아님)**: k8s 매니페스트(Deployment·HPA·PDB·readiness)와 롤링 배포 시 소비자 리밸런스 최소화(`static membership`, `group.instance.id`) 방법을 `docs/adr/ADR-011`에 기술.

---

## 15. 구현 로드맵 (요약, 상세는 IMPLEMENTATION_PLAN.md)

| Phase | 산출물 | 완료 기준(요약) |
|---|---|---|
| 0 스캐폴딩 | 모노레포, libs, Compose, CI 골격, ArchUnit | `make up` 후 전 서비스 헬스 OK, CI 녹색 |
| 1 주문 접수 | order-service 완성 | 멱등 POST, outbox 발행, k6 500 rps p99 측정치 |
| 2 풀필먼트 | fulfillment-service, 웨이브·컷오프 | 주문 → wave.closed 자동 발생, 이중 마감 없음 |
| 3 디스패치 코어 | 룰 엔진, 비용 모델, sweep-greedy-nn, 설명, route.assigned | 5,000 주문 계획 성공, 설명 조회 |
| 4 최적화 고도화 | LS 개선, savings, FAST 모드, 벤치마크 리포트 | 베이스라인 대비 ≥ 15% 비용 절감 문서화 |
| 5 트래킹·시뮬레이션 | tracking-service, sim-runner 기사, at-risk, 재계획 | 지연 주입 시 at-risk → 재계획 → revision 반영 |
| 6 백오피스 | ops-api 읽기 모델·커맨드, ops-web 4화면 | 운영자가 웨이브 조기 마감·재배정 수행 |
| 7 신뢰성·관측성·문서 | 대시보드, 알림, 카오스, 피크 시나리오, 포스트모템, ADR 정리, README | 피크 실측표, 카오스 검증 쿼리 0건 이상 유실 없음 |

Phase 3까지가 **최소 데모 가능 버전(MVP)** 이며, 이력서·면접에 바로 쓸 수 있는 상태다. Phase 4·7이 Staff 레벨 차별화 구간이다.

---

## 16. ADR 목록 (docs/adr/)

이 표는 `docs/adr/` 의 **파일과 1:1로 대응**한다. 문서 열이 `—` 인 항목은 결정 방향만 정해 두고
아직 ADR을 쓰지 않은 것이며, 해당 Phase에서 파일을 만들면서 이 표를 갱신한다.
(같은 표가 `docs/adr/README.md` 에도 있다. 둘은 함께 고친다.)

| ADR | 결정 | 대안 | 문서 |
|---|---|---|---|
| 001 | Gradle 멀티모듈 모노레포 | 서비스별 저장소 (포트폴리오 가독성 저하) | [ADR-001](adr/ADR-001-gradle-multi-module-monorepo.md) |
| 002 | DB-per-service + 폴링 Outbox 릴레이 | Debezium CDC(운영 복잡도), 2PC(불가) | [ADR-002](adr/ADR-002-db-per-service-polling-outbox.md) |
| 003 | JSON + JSON Schema 이벤트 계약 | Avro/Protobuf + Schema Registry(로컬 복잡도, 확장 경로만 기술) | [ADR-003](adr/ADR-003-json-schema-event-contracts.md) |
| 004 | 자체 휴리스틱(sweep-greedy-nn+ls) 기본 + Timefold 비교 | OR-Tools(JNI·배포 부담), Timefold 단독(블랙박스로는 알고리즘 역량 증명 약함) | — (Phase 4 예정) |
| 005 | Redis `SET NX` 락 + DB 낙관적 락 이중화 | PostgreSQL advisory lock(서비스별 DB 분리 시 범위 한계), Redisson | — (Phase 2 예정) |
| 006 | at-least-once + 멱등 소비자 | Kafka 트랜잭션/EOS(DB 쓰기와 원자성 불가) | [ADR-006](adr/ADR-006-at-least-once-idempotent-consumer.md) |
| 007 | 헥사고날 + ArchUnit 강제 | 계층형(경계 침식) | [ADR-007](adr/ADR-007-hexagonal-architecture-archunit.md) |
| 008 | 가상 스레드(I/O) + ForkJoin(CPU) 분리 | 전부 플랫폼 스레드 | — (Phase 4 예정) |
| 009 | URL 경로 API 버저닝(v1), 매핑은 `{version}` 자리표시자 | 헤더 버저닝(URL·로그·데모에서 안 보임), 미디어 타입 파라미터(캐시·프록시 복잡), 리터럴 `v1` + 버저닝 끄기(지원하지 않는 버전이 404 가 됨) | [ADR-009](adr/ADR-009-url-path-api-versioning.md) |
| 010 | 하버사인 × 도로계수 기본, OSRM 어댑터 선택 | 상용 지도 API(비용·키 관리) | — (Phase 3 예정) |
| 011 | 롤링 배포 시 소비자 static membership | 기본 리밸런스 | — (Phase 7 예정) |
| 012 | CQRS 읽기 모델을 ops-api에 집중 | 각 서비스에 조회 API 노출(서비스 간 동기 호출 증가) | — (Phase 6 예정) |
| 013 | 컨테이너 이미지 = Spring Boot Buildpacks(`bootBuildImage`) | Jib(플러그인 추가·Boot 4 검증 부담), 수동 Dockerfile(5배 유지보수) | [ADR-013](adr/ADR-013-container-image-buildpacks.md) |
| 014 | JDK 25 툴체인 자동 프로비저닝(foojay-resolver) | 로컬 JDK 수동 설치 전제(환경별 재현성 저하) | [ADR-014](adr/ADR-014-jdk25-toolchain-auto-provisioning.md) |
| 015 | Outbox 발행 실패를 결정적/일시적으로 나누고 결정적 실패만 격리 | 무한 재시도 유지(진행 보장 없음), DLQ 토픽 우회 발행(실패 원인과 순환), N회 후 자동 폐기(이벤트 소실) | [ADR-015](adr/ADR-015-outbox-publish-side-quarantine.md) |
| 016 | 레디니스에서 Kafka 브로커 연결 제외 | 코드에 Kafka 프로브 추가(§8.2 완충 설계 붕괴), 기동 시 1회 검사(기동 순서 의존성) | [ADR-016](adr/ADR-016-readiness-excludes-kafka.md) |
| 017 | 주문 상태 머신이 순서 뒤바뀜을 흡수(`PLANNED → DELIVERED` 추가 + 진행 단계 비교) | 백오프 재시도에 맡김(도착 상한 없음 → 정상 배송이 DLQ), `delivery.status` 키를 orderId 로 변경(다른 소비자의 라우트 단위 순서가 깨짐), 모든 전이 허용(불변규칙 6 포기) | [ADR-017](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md) |
| 018 | 멱등 잠금은 Redis 키(PX 30000)가 잡고 DB `idempotency_keys` 에는 `DONE` 만 기록 | DB 에 `IN_PROGRESS` 선커밋(프로세스 사망 시 그 멱등 키가 영구히 409), 짧은 `expires_at` 으로 자가 만료(정리 배치가 또 필요), Redis 없이 PK 충돌만(중복 요청이 주문 INSERT 까지 하고 롤백) | [ADR-018](adr/ADR-018-idempotency-lock-in-redis-record-in-db.md) |
| 019 | 멱등 기록 보존 7일 + `status` 컬럼 제거 + `ON CONFLICT DO NOTHING` | 무한 보존(테이블 무제한 증가), 24h(Redis TTL 과 같아 DB 경로의 의미 절반 상실), 30일(DLQ 숫자를 빌려 옴) | [ADR-019](adr/ADR-019-idempotency-record-retention-7-days.md) |
| 020 | 컷오프는 order-service 가 계산해 이벤트로 전달, 웨이브 마감은 `cutoffAt + grace`, 못 지킨 약속은 `promiseRevised` 로 되돌려 알림 | fulfillment 가 컷오프 재계산(같은 표를 두 곳에서 관리), grace 없이 엄격 마감(정상 지연이 약속을 깸), 조용히 다음 웨이브로 밀기(고객이 나중에 알게 됨), `promiseRevised` 를 선택 필드로(소비자에 죽은 분기) | [ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) |

| 023 | `fulfillment_orders` 30일 · `waves` 90일 보존, 파티션이 아니라 배치 삭제 | 무한 보존(월 4.5M 행 증가), 14일(DLQ 30일째 조사에서 기록이 없다), 90일(조사 창이 DLQ 를 넘어설 근거 없음), 날짜 파티셔닝(파티션 키가 PK 에 들어가 ADR-022 의 단독 PK 보장이 약해짐), 상태 무관 삭제(진행 중 주문이 지워짐) | [ADR-023](adr/ADR-023-fulfillment-retention.md) |
| 022 | fulfillment 에 주문 단위 애그리거트 `fulfillment_orders` 도입, `wave_orders` 드롭 | 취소 마커 테이블 + `wave_orders(order_id)` 인덱스(사실이 두 곳에 흩어지고 UNSERVICEABLE 은 여전히 답 못 함), `wave_orders` 에 컬럼 추가(복합 PK 라 웨이브 없는 상태를 표현 못 함), 상태를 이벤트로만 두기(재처리·운영 질의에서 답 못 함), `processed_events` 재사용(의미·보존 기간이 다름) | [ADR-022](adr/ADR-022-fulfillment-order-aggregate.md) |
| 021 | 권역 시드를 order-service 지오코더의 출력 집합에서 파생(권역 91개) | 60개를 손으로 고르기(31개 셀이 조용히 UNSERVICEABLE), 지오코더의 지터 축소(머지된 동작 변경 + 최적화 비교 무의미), 권역 키를 geohash4 로(캠프 단위 병렬성 붕괴), 양쪽에 목록을 각자 보관(한쪽만 고치는 날이 온다) | [ADR-021](adr/ADR-021-zone-seed-derived-from-geocoder.md) |

| 024 | 웨이브 계획 완료를 `plan.completed.v1` 로 알린다(+`PLAN_FAILED → PLANNED`, 마지막 두 전이에 축 규칙) | `route.assigned` 를 fulfillment 가 소비(첫 라우트면 계획 중인데 완료, 전부면 개수를 소비자가 모름), `route.assigned` 에 `routeCount`/`isLast` 추가(웨이브 사실이 라우트 수만큼 반복 + 재정렬 시 영영 미완료), dispatch 에 동기 조회(불변규칙 4), 수명주기에서 마지막 두 전이 삭제(ADR-023 정리 배치가 성립하지 않음) | [ADR-024](adr/ADR-024-plan-completed-event.md) |

| 025 | 웨이브 편입은 `FOR SHARE`·마감만 `FOR UPDATE`, `order_count` 는 마감 시 집계 | 편입도 `FOR UPDATE`(§8.2 피크에서 웨이브 행이 처리량 상한), 락 없이 낙관적 락만(편입은 웨이브 행을 쓰지 않아 충돌로 안 잡힌다 — 마감된 웨이브에 주문이 샌다), 원자적 `order_count` 증감(배타 락을 이름만 바꾼 것 + 취소 경로 드리프트), advisory lock, 웨이브 샤딩(계획 단위가 쪼개진다) | [ADR-025](adr/ADR-025-wave-admission-share-lock.md) |

013·014는 Phase 0 스캐폴딩 중에, 015·016은 Phase 0 마감 감사 중에, 017은 Phase 1 리스너 설계 중에 확정되어 추가됐다. 020·021·022·023은 Phase 2 착수 시점에 — 코드보다 먼저 — 확정했다. 023은 022가 남긴 보존 문제를 닫으면서, ADR-020 의 지각 도착 경로에 상한이 없다는 것(20일 묵은 replay 가 새 배송 약속을 만든다)을 함께 잡았다. 021은 §16 표에 없던 항목으로, 부록 A 의 권역 60개가 지오코더의 출력을 덮지 못한다는 것을 <strong>세어 보고</strong> 알게 되어 추가했다. 024는 Phase 2-3 에서 `WaveStatus` 의 마지막 두 전이에 트리거가 없다는 것을 발견해 추가했다 — §5.2 의 수명주기와 §4.1 의 소비자 표가 어긋나 있었고, 그 어긋남이 ADR-023 의 정리 배치를 조용히 무한 보존으로 만들고 있었다.

---

## 17. 리스크와 미결 사항

| 리스크 | 영향 | 대응 |
|---|---|---|
| 범위 과대 (서비스 5개 + 프론트 + 도구) | 미완성 상태로 지원 | Phase 3 MVP 우선, Phase 5·6은 축소 가능(ops-web 2화면) |
| 최적화 품질이 베이스라인과 차이 없음 | 핵심 어필 실패 | 벤치마크를 Phase 3부터 상시 실행, LS 파라미터 튜닝 기록 |
| 합성 지리 데이터의 비현실성 | 면접에서 지적 | 데이터 생성 가정을 문서화, OSRM 어댑터로 실도로 거리 1회 검증 |
| Spring Boot 4 호환 라이브러리 미성숙(springdoc, Resilience4j 등) | 빌드 실패 | Phase 0에서 호환 버전 확정, 불가 시 대체(Boot 내장 HTTP 클라이언트 재시도 등) |
| 노트북 자원으로 목표치 미달 | SLO 미충족 | 목표는 "측정·문서화"가 우선, 미달 시 원인 분석을 문서로 |

**[결정 필요] 목록 (미해소)**

| # | 항목 | 결정 시점 |
|---|---|---|
| 4 | Redis vs Valkey | Redis 8로 진행, 라이선스 이슈 발생 시 재검토(명령 호환) |
| 5 | ops-web 지도 타일 서버 정책 | Phase 6 |
| 6 | Timefold 실험 포함 여부 | Phase 4 stretch (ADR-004와 함께) |

**해소된 항목**: (1) 도메인 모델과 JPA 엔티티 분리 → **분리한다**, ADR-007로 확정. (2) 고객 주문 API 키 → **생략한다**(무인증), Phase 1 착수 시 확정 — 근거와 그 대가는 §10. Phase 6 이월도 고르지 않았다: 나중에 붙이면 k6·sim-runner·통합 테스트를 소급 수정해야 한다. (3) 이미지 빌드 Jib vs Buildpacks → **Buildpacks**, ADR-013으로 확정.

Phase 0 마감에서 설계서 내부 모순 두 건도 ADR로 확정했다(원래 `[결정 필요]` 목록에는 없던 항목이다): outbox 발행 측 독약 행 처리 → ADR-015, 레디니스의 Kafka 조건 → ADR-016.

---

## 부록 A. 시드 데이터·시뮬레이션 시나리오

- FC 3개, 캠프 10개(FC당 2·5·3), **권역 91개**(캠프당 6~13), 차량 200대(캠프당 20: 일반 14, 냉장 4, 대형 2), 기사 200명.
- 좌표: 수도권(위도 37.16–37.78, 경도 126.61–127.22). 이 범위는 order-service 의 `PostalPrefixGeocoder`
  가 실제로 만들어 내는 좌표의 경계다 — 우편번호 앞 2자리 앵커 19개 × 세 번째 자리 10단계 × 주소
  해시 지터(±0.004°).
- **권역 수·FC당 캠프 수는 어림수가 아니라 계산값이다** ([ADR-021](adr/ADR-021-zone-seed-derived-from-geocoder.md)).
  권역은 위 지오코더가 만들어 낼 수 있는 geohash5 셀 <em>전부</em>이고 세어 보면 91개다. 60개를
  손으로 고르면 31개 셀의 주소가 전부 `UNSERVICEABLE` 이 되는데, 그것이 설계된 실패 경로와
  구별되지 않는다. FC당 캠프가 2·5·3 인 것도 같은 이유다 — 수도권 우편번호 19개 접두어 중 8개가
  서울이라 캠프가 서울에 몰린다.
- 시드는 Flyway `R__seed_*.sql` 로 넣는다(Phase 2 확정). `sim-runner` 는 §5.6 대로 REST 전용으로
  남아 남의 서비스 DB 에 쓰지 않는다(불변규칙 3).
- 시나리오 YAML: `smoke`(200 주문, 1 캠프), `normal-day`(30k), `peak-day`(150k, 컷오프 전 버스트), `cold-heavy`(냉장 40%), `late-injection`(지연 확률 15%, 실패 3%).

## 부록 B. 면접 스토리 매핑

| 면접 주제 | 이 프로젝트의 근거 |
|---|---|
| 시스템 설계·트레이드오프 | §3.3 의존성 규칙, ADR-002/006, 열화 모드(§6.7) |
| 알고리즘·최적화 | §6 파이프라인, 벤치마크 표, 설명 가능성 |
| 대용량·고가용성 | §8 피크 모델·장애 모드·멱등성 지점, 카오스 검증 |
| 도메인 모델링·JPA | 애그리거트 경계, 낙관적 락, N+1 카운터 테스트 |
| 운영·관측성 | traceId 한 줄 추적 데모, 런북, 포스트모템 |
| 새 기술 검토 | ADR-004(Timefold/OR-Tools 비교), ADR-010(OSRM) |
| 코드 품질·자동화 | ArchUnit, 커버리지 게이트, CI 스모크, 벤치마크 회귀 |

## 부록 C. 용어집 보충

- **geohash7**: 약 153 m × 153 m 셀. stop 통합·거리 캐시 키로 사용. **geohash5**: 약 4.9 km × 4.9 km, 권역 매핑에 사용.
- **Sweep**: 창고 기준 각도 순으로 고객을 훑으며 용량 한도에서 클러스터를 자르는 고전 VRP 휴리스틱.
- **Clarke-Wright savings**: 두 고객을 한 라우트로 합칠 때 절감되는 거리 `s(i,j)=d(0,i)+d(0,j)-d(i,j)`가 큰 순으로 병합하는 휴리스틱.
- **2-opt / Or-opt**: 라우트 내 구간 뒤집기 / 소구간 이동으로 거리를 줄이는 지역 탐색.
