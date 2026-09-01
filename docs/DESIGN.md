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
| dispatch-service | 룰 엔진, 최적화, 라우트/차량/기사 관리, 재계획 | vehicles, drivers, candidates, plans, routes, rules | route.assigned, order.dispatched, plan.failed | fulfillment.planned, wave.closed, delivery.at-risk |
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
| dawnline.fulfillment.planned.v1 | orderId | fulfillment | dispatch, ops | FC·캠프·권역·웨이브 결정 |
| dawnline.wave.closed.v1 | campId | fulfillment | dispatch, ops | 컷오프 도달, 계획 시작 신호 |
| dawnline.route.assigned.v1 | routeId | dispatch | tracking, ops | 라우트 확정 (stops 포함) |
| dawnline.order.dispatched.v1 | orderId | dispatch | order, ops | 주문이 라우트에 배정됨 |
| dawnline.plan.failed.v1 | waveId | dispatch | ops | 계획 실패/부분 실패 |
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
  "placedAt": "…"
}
```

**fulfillment.planned.v1** — order.placed 스냅샷 + `fcId, campId, zoneId, waveId, waveCutoffAt`

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

### 4.4 전달 보장: Outbox + at-least-once + 멱등 소비자 (ADR-006)

- **발행**: 도메인 변경과 `outbox_events` INSERT를 같은 DB 트랜잭션에서 수행. 별도 릴레이(`OutboxRelay`, 폴링 100ms, 배치 500, `FOR UPDATE SKIP LOCKED`)가 Kafka로 발행 후 `published_at` 기록. 릴레이는 다중 인스턴스 안전(SKIP LOCKED).
- **소비**: 리스너는 `processed_events(event_id, consumer)`를 먼저 INSERT(같은 트랜잭션)한다. 이미 있으면 처리 생략. 비즈니스 로직 + processed 기록 + 자기 outbox 기록이 하나의 트랜잭션.
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

구분 기준은 예외 타입이다: Kafka `send()` 이전 단계의 예외와 직렬화 예외는 결정적, 전송·네트워크 예외는 일시적. 판단이 애매한 예외는 일시적으로 취급한다(격리는 사람의 개입을 요구하므로 보수적으로).

이 구분이 필요한 이유는 두 실패의 성질이 정반대이기 때문이다. 결정적 실패는 **몇 번을 재시도해도 같은 결과**라서, 재시도를 유지하면 그 행이 `created_at` 순서상 맨 앞에 서서 뒤의 모든 이벤트를 영구히 막는다(head-of-line blocking). 일시적 실패는 반대로 **기다리면 풀린다** — 여기서 행을 격리하면 브로커가 잠깐 흔들렸다는 이유로 멀쩡한 이벤트가 사람 손을 기다리게 된다.

격리는 §4.5의 순서 보장을 **그 파티션 키에 한해** 깨뜨린다. 격리된 행 뒤에 같은 키의 이벤트가 있으면 그것이 먼저 발행된다. 이는 의도된 것이다 — 대안은 서비스 전체의 이벤트 발행이 멈추는 것이고, 격리는 알림(§9.4)과 함께 사람에게 넘어간다.

격리된 행의 복구는 수동이다: 원인 수정 → `UPDATE outbox_events SET failed_at = NULL, publish_attempts = 0 WHERE id = …` (RB-05). ops-api 격리 조회·재큐 엔드포인트는 Phase 6 범위(§5.5 커맨드 목록에 추가).

### 4.7 스키마 진화 규칙

- 같은 major 안에서는 **추가만** 허용(필드 추가, enum 값 추가). 소비자는 알 수 없는 필드를 무시해야 한다(`FAIL_ON_UNKNOWN_PROPERTIES=false`).
- 필드 삭제·의미 변경·타입 변경은 새 토픽 `v2`로 발행하고 소비자가 이관될 때까지 v1·v2 병행 발행(dual-publish).
- 계약 테스트: 발행자는 스키마 검증, 소비자는 `contracts/events/examples/*.json`으로 역직렬화 테스트.

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

```
PLACED ──(fulfillment.planned)──▶ PLANNED ──(order.dispatched)──▶ DISPATCHED ──(delivery COMPLETED)──▶ DELIVERED
  │                                  │                                 └──(delivery FAILED)──▶ FAILED
  └──────── cancel ──────────────────┴──▶ CANCELLED     (DISPATCHED 이후 취소 불가 → 409)
```

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

CREATE TABLE idempotency_keys (
  idem_key      VARCHAR(64) PRIMARY KEY,
  request_hash  CHAR(64) NOT NULL,                   -- SHA-256(body)
  status        VARCHAR(12) NOT NULL,                -- IN_PROGRESS | DONE
  response_code SMALLINT,
  response_body JSONB,
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL
);

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

CREATE TABLE processed_events (
  event_id     UUID NOT NULL,
  consumer     VARCHAR(64) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (event_id, consumer)
);
```

**멱등 처리 흐름 (POST /orders)**
1. Redis `SET idem:order:{key} IN_PROGRESS NX PX 30000` — 실패 시 DB `idempotency_keys` 조회: `DONE`이면 저장된 응답 반환, `IN_PROGRESS`면 409.
2. 본문 해시가 기존과 다르면 422 (같은 키, 다른 요청).
3. 트랜잭션: orders + order_items + outbox + idempotency_keys(DONE) INSERT.
4. Redis 키를 `DONE`으로 갱신 (TTL 24h). Redis 장애 시 DB 경로만으로 동작(성능 저하, 정확성 유지).

**Redis**: `idem:order:*`, 레이트 리밋 `rl:customer:{id}` (토큰 버킷 Lua, 기본 60 req/min).

### 5.2 fulfillment-service

**책임**: 주문마다 (FC, 캠프, 권역, 웨이브)를 결정하고, 컷오프에 웨이브를 닫는다.

**FC 선택 규칙 (순서대로 필터 → 점수)**
1. 티어 지원 여부 (`fulfillment_centers.tiers`에 포함)
2. 냉장 필요 시 `supports_cold`
3. 재고 가용 (`inventory_stock` 스텁, 모든 SKU 가용 시 통과) — 실서비스에서는 재고 서비스 연동 지점
4. 주소 geohash5 → `zones` 매핑으로 캠프 결정; 캠프의 `fc_id` 후보
5. 복수 후보면 Redis `GEOSEARCH geo:fc FROMLONLAT … BYRADIUS 50 km ASC`로 최근접 선택
6. 어느 것도 없으면 주문을 `UNSERVICEABLE`로 표시하고 `fulfillment.planned`에 `outcome=UNSERVICEABLE`로 발행 (주문 서비스는 이를 받아 상태 `FAILED`, 사유 기록)

**Wave 수명주기**

```
OPEN ──(cutoff 도달, 락 획득)──▶ CLOSING ──(wave.closed 발행 완료)──▶ CLOSED ──(route.assigned 수신)──▶ PLANNED
                                                                        └──(plan.failed)──▶ PLAN_FAILED
```
- 웨이브는 (campId, tier, cutoffAt)당 1개. 주문 편입 시 없으면 생성(`INSERT … ON CONFLICT DO NOTHING` 후 재조회).
- 컷오프 스케줄러: 매 30초 `cutoff_at <= now() AND status='OPEN'` 조회 → 웨이브별 Redis 락 `lock:wave:{id}` (SET NX PX 60000, Lua 언락) → `CLOSING` 전이 + `wave.closed` outbox. 락 실패는 다른 인스턴스가 처리 중이라는 뜻이므로 스킵.
- 컷오프 이후 도착한 같은 티어 주문은 **다음 웨이브**로 편입. `CLOSING/CLOSED` 웨이브에는 편입 불가(낙관적 락으로 경합 차단).

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
CREATE TABLE wave_orders (wave_id UUID REFERENCES waves, order_id UUID, fc_id UUID, zone_id UUID, added_at TIMESTAMPTZ,
  PRIMARY KEY (wave_id, order_id));
```

**Redis**: `geo:fc`, `geo:camp` (GEOADD, 기동 시 적재·변경 시 갱신), `zone:geohash5:{prefix}` → zoneId 캐시 (TTL 10m), `lock:wave:{id}`.

### 5.3 dispatch-service

**책임**: 후보 적재, 차량·기사 자원, 룰 엔진·최적화 실행, 라우트 확정·발행, 재계획. 알고리즘 상세는 §6.

**Plan 상태 머신**

```
REQUESTED ──▶ PLANNING ──▶ PLANNED ──▶ PUBLISHED
                 └──(예외/시간초과)──▶ FAILED (plan.failed 발행, 운영자 재실행 가능)
```
- `route_plans.wave_id`는 UNIQUE. `wave.closed`가 중복 도착해도 두 번째는 기존 plan을 발견하고 종료(멱등).
- 계획 중 인스턴스가 죽으면 `PLANNING` 상태로 남는다. 스타트업/스케줄러가 `PLANNING`이고 `started_at`이 10분 경과한 plan을 `REQUESTED`로 되돌려 재실행한다. 결과 쓰기는 plan 단위 트랜잭션이므로 부분 결과가 발행되지 않는다.

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
5. 재계획도 라우트당 10분 쿨다운.

### 6.9 벤치마크 방법

- 데이터셋: `tools/benchmark/datasets/` — `small`(500 주문/5 차량), `medium`(2,000/20), `large`(5,000/40), `peak`(15,000/60), 각각 seed 고정 생성. 좌표는 서울 근사 격자(캠프 중심 반경 8 km, 밀도 불균일).
- 지표: 총비용, 총거리, 계획 시간, 미배정 수, 지각 stop 수·평균 지각분, 차량 사용 대수.
- 각 전략 × 데이터셋을 5회 반복, 중앙값·p95 기록. 결과는 `docs/benchmarks/YYYY-MM-DD.md`에 표와 함께 커밋. README 상단에 최신 표를 링크.
- 회귀 방지: CI에서 `small`을 1회 실행해 기본 전략 비용이 베이스라인보다 나쁘면 실패.

---

## 7. 데이터 저장소 설계

### 7.1 PostgreSQL

- **DB-per-service**: Compose에서는 PostgreSQL 인스턴스 1개에 서비스별 데이터베이스(`dawnline_order` 등) 분리. 접속 계정도 분리해 교차 접근을 물리적으로 차단.
- 마이그레이션: Flyway, `V<n>__<desc>.sql` (서비스별). JPA `ddl-auto`는 `validate`만 허용.
- ID: UUIDv7 (애플리케이션 생성, 시간순 → 인덱스 지역성). PostgreSQL 18의 `uuidv7()`은 사용하지 않는다(ID를 DB 왕복 전에 알아야 outbox·이벤트에 쓸 수 있음).
- 인덱스는 위 DDL 명시분 외에 추가 금지(추가 시 EXPLAIN 근거를 PR에 첨부).
- 파티셔닝: `shipment_events`(일 단위), `outbox_events`는 발행 후 7일 지난 행을 배치 삭제(파티션 대신 삭제, 규모가 작음).
- 낙관적 락(`version`)은 상태 전이가 있는 모든 애그리거트에 적용. 비관적 락은 웨이브 편입의 `waves` 행 `SELECT … FOR UPDATE`(짧은 트랜잭션)에만 허용.
- N+1 방지: 컬렉션 로딩은 `@EntityGraph` 또는 명시 fetch join. 테스트에서 Hibernate statement 카운터로 쿼리 수 상한 검증.

### 7.2 Redis 사용 카탈로그

| 키 패턴 | 자료구조 | 서비스 | TTL | 장애 시 폴백 |
|---|---|---|---|---|
| `idem:order:{key}` | STRING | order | 24h | DB `idempotency_keys`만으로 동작 |
| `rl:customer:{id}` | STRING(Lua 토큰버킷) | order | 60s | 레이트 리밋 비활성(허용) |
| `geo:fc`, `geo:camp` | GEO | fulfillment | 없음(기동 시 재적재) | DB 전체 조회 후 메모리 하버사인 |
| `zone:geohash5:{p}` | STRING | fulfillment | 10m | DB 조회 |
| `lock:wave:{id}`, `lock:plan:{waveId}` | STRING NX | fulfillment, dispatch | 60s | 단일 인스턴스 가정 하 DB 낙관적 락으로 중복 방지 유지 |
| `rules:camp:{id}:v{n}` | STRING(JSON) | dispatch | 1h | DB 조회 |
| `dist:{gh7a}:{gh7b}` | STRING | dispatch(OSRM 시) | 1d | 하버사인 |
| `route:{id}:progress` | HASH | dispatch/tracking | 2d | DB 조회 |
| `driver:{id}:pos` | GEO | tracking | 1h | 없음(시각화용) |
| `route:{id}:atrisk:cooldown` | STRING NX | tracking | 5m | 중복 at-risk 허용(멱등 소비자가 흡수) |

원칙: Redis는 **성능·조정(coordination)** 용도이며 **유일한 진실 저장소가 아니다**. 어떤 키가 사라져도 정확성은 DB로 회복된다.

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
| 정시 배송률 (시뮬레이션, 지연 주입 5%) | ≥ 97% |
| Outbox 지연 | p95 ≤ 2초 |

### 8.2 피크 시나리오 모델

- 평시: 캠프 10개 × 3,000 주문/일 = 30,000/일, 최대 50 rps.
- 피크(연말 세일): 5배 = 150,000/일, 컷오프 직전 1시간에 30% 집중 → 최대 ~600 rps 버스트.
- 완충 지점: (1) 주문 API는 외부 호출 없이 INSERT만 → 수평 확장으로 흡수 (2) Kafka가 하류 처리 지연을 흡수 (3) 웨이브가 배치 경계를 만들어 최적화 부하를 컷오프 시점으로 모음 (4) 열화 모드(§6.7).
- 확장 경로: order-service 인스턴스 N개(무상태), Kafka 파티션 ≥ 소비자 수, dispatch는 캠프 파티션 단위 병렬, PostgreSQL 커넥션 풀 총합 관리(HikariCP, 인스턴스당 10).

### 8.3 백프레셔

- Kafka 소비자: `max.poll.records=100`, 처리 중 `pause()`, 완료 후 `resume()`. 리스너 컨테이너 concurrency = 파티션 수 이하.
- dispatch 계획 큐: `wave.closed`는 캠프별 직렬이므로 큐 자체가 백프레셔. 연속 지연 감지 시 FAST 모드.
- 주문 API: 고객별 레이트 리밋 + 전역 `Bulkhead`(동시 요청 상한, 초과 시 429 + `Retry-After`).
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

- 레디니스: DB 마이그레이션 완료 + (fulfillment) GEO 적재 완료. Kafka 브로커 연결은 레디니스 조건에 넣지 않는다 — 브로커 장애 시에도 쓰기 경로는 outbox로 정상 동작해야 하기 때문이다(§8.4, ADR-016). 브로커 상태는 레디니스가 아니라 outbox 지연·랙 알림으로 감시한다.
- 그레이스풀 셧다운: HTTP 드레인 30초, Kafka 소비자 커밋 후 종료, 진행 중 계획은 `PLANNING` 유지(재실행 경로가 회수).

---

## 9. 관측성과 운영

### 9.1 커스텀 메트릭 (Micrometer)

| 메트릭 | 타입 | 라벨 |
|---|---|---|
| `dawnline_orders_placed_total` | counter | tier, camp |
| `dawnline_outbox_lag_seconds` | gauge | service |
| `dawnline_outbox_unpublished` | gauge | service |
| `dawnline_outbox_failed` | gauge | service — 격리된(미해결) outbox 행 수 (§4.6) |
| `dawnline_event_processed_total` | counter | consumer, eventType, outcome(ok/dup/rejected/dlq) |
| `dawnline_wave_orders` | gauge | camp, tier |
| `dawnline_plan_duration_seconds` | histogram | strategy, mode |
| `dawnline_plan_cost_krw` | gauge | camp |
| `dawnline_plan_unassigned` | gauge | camp |
| `dawnline_plan_degraded_total` | counter | camp |
| `dawnline_delivery_on_time_ratio` | gauge | camp |
| `dawnline_at_risk_total` | counter | camp |

Kafka 소비자 랙·프로듀서 지표는 Spring Kafka 기본 지표 사용.

### 9.2 트레이싱

OpenTelemetry(Micrometer Tracing → OTLP → Tempo). Kafka 헤더로 `traceparent` 전파. 하나의 주문 traceId로 order → fulfillment → dispatch(계획은 별도 span, waveId 태그) → tracking을 Grafana에서 한 줄로 볼 수 있어야 한다(데모 핵심).

### 9.3 로깅

JSON 구조 로그(traceId, spanId, service, eventId, orderId/waveId/routeId MDC). 개인정보(주소 전체)는 로그에 남기지 않는다(우편번호·geohash만).

### 9.4 대시보드·알림 (저장소에 JSON으로 커밋)

- `Order Intake`: rps, p99, 429/5xx, outbox 지연
- `Waves & Plans`: 웨이브별 주문 수, 계획 시간, 비용, 미배정, degraded
- `Delivery`: 정시율, at-risk, 실패, 라우트 진행
- `Platform`: consumer lag, DLQ 건수, DB 커넥션, JVM
- 알림 규칙: outbox 지연 > 30s, `dawnline_outbox_failed` > 0(격리 행 발생 — RB-05), DLQ 신규 > 0, consumer lag > 1,000, 계획 시간 p95 > 45s, 정시율 < 95%

### 9.5 런북 (`docs/runbooks/RB-0x.md`)

RB-01 Kafka 복구 · RB-02 DB 장애 · RB-03 Redis 복구 · RB-04 계획 정체/강제 재실행 · RB-05 DLQ 재처리·outbox 격리 재큐(§4.6) · RB-06 피크 대비 체크리스트(파티션·인스턴스·룰 파라미터 사전 점검).

---

## 10. 보안 (최소 범위)

- 고객 주문 API: 데모용 API 키 헤더(`X-Api-Key`) `[결정 필요: 생략 가능]`. 
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
| 문서 | springdoc-openapi | Spring Boot 4 호환 최신판 (빌드 시 확인) | OpenAPI 3 자동 생성, `contracts/openapi/`로 내보내기 |
| 회복탄력성 | Resilience4j | Boot 4 호환 최신판 (빌드 시 확인) | Bulkhead, Retry, CircuitBreaker(OSRM 어댑터) |
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
│   └── openapi/*.yaml                # 빌드 시 생성물 커밋
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

각 서비스 모듈은 `libs/*`만 의존한다. 서비스 간 소스 의존은 금지(ArchUnit + Gradle 의존성 규칙).

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

**ArchUnit 규칙 목록**: (1) `domain`은 `org.springframework`, `jakarta.persistence` 의존 금지 (2) `application`은 `adapter` 의존 금지 (3) `com.dawnline.<svc>`는 다른 `<svc>` 패키지 참조 금지 (4) Kafka 리스너 클래스는 `adapter.in.messaging`에만 존재 (5) `@Transactional`은 `application` 계층에만.

**결정론**: 최적화 테스트는 seed 고정. 시간은 `Clock` 주입으로 제어. Testcontainers 재사용(`testcontainers.reuse.enable=true`)으로 로컬 실행 시간 단축.

---

## 14. CI/CD와 배포

**ci.yml (PR·main)**: checkout → JDK 25 → Gradle 캐시 → `./gradlew check`(단위+ArchUnit+계약+JaCoCo 게이트) → 통합 테스트(Testcontainers, Docker 서비스) → `benchmark small` 회귀 체크 → 이미지 빌드(Buildpacks, ADR-013) → Compose 스모크(주문 20건 E2E) → 결과 아티팩트(리포트, OpenAPI).

**release.yml (태그 `v*`)**: 이미지 GHCR 푸시(태그·`latest`), SBOM 생성, GitHub Release 노트.

**로컬 실행**: `make up`(전체 스택), `make demo`(시드 + smoke 시나리오 + Grafana URL 출력), `make peak`(피크 시나리오), `make down`. Makefile은 Compose 명령 래퍼다.

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
| 009 | URL 경로 API 버저닝(v1) | 헤더 버저닝 | — (Phase 1 예정) |
| 010 | 하버사인 × 도로계수 기본, OSRM 어댑터 선택 | 상용 지도 API(비용·키 관리) | — (Phase 3 예정) |
| 011 | 롤링 배포 시 소비자 static membership | 기본 리밸런스 | — (Phase 7 예정) |
| 012 | CQRS 읽기 모델을 ops-api에 집중 | 각 서비스에 조회 API 노출(서비스 간 동기 호출 증가) | — (Phase 6 예정) |
| 013 | 컨테이너 이미지 = Spring Boot Buildpacks(`bootBuildImage`) | Jib(플러그인 추가·Boot 4 검증 부담), 수동 Dockerfile(5배 유지보수) | [ADR-013](adr/ADR-013-container-image-buildpacks.md) |
| 014 | JDK 25 툴체인 자동 프로비저닝(foojay-resolver) | 로컬 JDK 수동 설치 전제(환경별 재현성 저하) | [ADR-014](adr/ADR-014-jdk25-toolchain-auto-provisioning.md) |
| 015 | Outbox 발행 실패를 결정적/일시적으로 나누고 결정적 실패만 격리 | 무한 재시도 유지(진행 보장 없음), DLQ 토픽 우회 발행(실패 원인과 순환), N회 후 자동 폐기(이벤트 소실) | [ADR-015](adr/ADR-015-outbox-publish-side-quarantine.md) |
| 016 | 레디니스에서 Kafka 브로커 연결 제외 | 코드에 Kafka 프로브 추가(§8.2 완충 설계 붕괴), 기동 시 1회 검사(기동 순서 의존성) | [ADR-016](adr/ADR-016-readiness-excludes-kafka.md) |

013·014는 Phase 0 스캐폴딩 중에, 015·016은 Phase 0 마감 감사 중에 확정되어 추가됐다.

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
| 2 | 고객 주문 API 키(`X-Api-Key`) 적용 여부 | Phase 1 |
| 4 | Redis vs Valkey | Redis 8로 진행, 라이선스 이슈 발생 시 재검토(명령 호환) |
| 5 | ops-web 지도 타일 서버 정책 | Phase 6 |
| 6 | Timefold 실험 포함 여부 | Phase 4 stretch (ADR-004와 함께) |

**해소된 항목**: (1) 도메인 모델과 JPA 엔티티 분리 → **분리한다**, ADR-007로 확정. (3) 이미지 빌드 Jib vs Buildpacks → **Buildpacks**, ADR-013으로 확정.

Phase 0 마감에서 설계서 내부 모순 두 건도 ADR로 확정했다(원래 `[결정 필요]` 목록에는 없던 항목이다): outbox 발행 측 독약 행 처리 → ADR-015, 레디니스의 Kafka 조건 → ADR-016.

---

## 부록 A. 시드 데이터·시뮬레이션 시나리오

- FC 3개, 캠프 10개(FC당 3~4), 권역 60개(캠프당 6), 차량 200대(캠프당 20: 일반 14, 냉장 4, 대형 2), 기사 200명.
- 좌표: 서울 중심 근사 격자(위도 37.45–37.65, 경도 126.85–127.15). 캠프 중심에서 반경 8 km 안에 밀도 불균일(가우시안 혼합)로 주소 생성.
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
