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
| 새로운 기술 도입 검토 | 자체 휴리스틱을 **불가능의 경계**와 재는 ADR(외부 솔버 도입을 *조건과 함께* 기각), 벤치마크 하네스 | §6.6, §6.9, ADR-004 |
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
                                                      ├──delivery.status──▶ [order-service], [ops-api], [dispatch-service]
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
| dispatch-service | 룰 엔진, 최적화, 라우트/차량/기사 관리, 재계획 | vehicles, drivers, candidates, plans, routes, rules | route.assigned, order.dispatched, plan.completed, plan.failed | fulfillment.planned, wave.closed, order.cancelled, delivery.status, delivery.at-risk |
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
| dawnline.delivery.status.v1 | routeId | tracking | order, **dispatch**, ops | ARRIVED/COMPLETED/FAILED |
| dawnline.delivery.at-risk.v1 | routeId | tracking | dispatch, ops | 지연 위험 감지 |
| dawnline.delivery.route-departed.v1 | routeId | tracking | ops | 라우트가 캠프를 떠났다 (§5.4 `DEPARTED_CAMP`). 계약은 소비자가 먼저 정의했고 tracking 이 낸다(2026-09-24, 묶음 B) — 출발 스캔이 배송을 실제로 옮겼을 때 라우트에 하나 |
| `<topic>.dlq` | 원본 키 | 각 소비자 | 운영자 | 재처리 실패 메시지 |

**dispatch 가 `delivery.status` 를 소비한다** (2026-09-05 결정). 처음에는 소비자가 order 와 ops 뿐이었고, 그
결과 dispatch 는 **자기 라우트의 배송이 어디까지 갔는지 모른다.** 그것이 막고 있는 것이 셋이다.

- §6.8 부분 재계획은 "미완료 stop 만" 다시 푼다. 어디까지 완료됐는지를 모르면 그 문장이 성립하지 않는다.
- §7.2 의 `route:{id}:progress`(HASH: nextSeq, completed, failed)는 소유자가 dispatch/tracking 인데,
  dispatch 쪽 값을 채울 입력이 없었다. **이 줄은 2026-09-23 에 근거를 잃었다** — 그 키는
  지웠다(§7.2 표 아래). 지우지 않고 남기는 이유는 *그때 소비자 목록을 바꾼 판단*이 이 셋을
  근거로 했기 때문이고, 셋 중 하나가 나중에 사라졌다는 것이 나머지 둘을 무르지 않는다.
- §6.10 넷째 분기(배송이 끝난 뒤 도착한 취소를 거부)와 `dawnline_cancel_too_late_total` 이
  **구조적으로 발화하지 않는다** — `route_stops.status` 를 옮기는 코드가 없기 때문이다.

셋 다 같은 결손의 증상이라 취소 분기만의 문제가 아니고, 그래서 Phase 5 까지 미루지 않고 여기서
소비자 목록을 바꾼다. **계약은 그대로 쓸 수 있다** — `delivery.status.v1` 은 이미
`routeId`·`stopSeq`·`orderIds` 를 required 로 들고 있어서 stop 을 지목할 수 있다(확인 2026-09-05).
추가 필드가 필요 없으므로 additive 변경도 없다. 구현은 tracking 이 이 이벤트를 실제로 내는
Phase 5 이고(§5.4), 그때 dispatch 리스너 + `route_stops.status` 전이 + ADR 을 함께 쓴다.

**들어왔다** (2026-09-22, Phase 5-5, [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md)).
계약은 예고대로 그대로다 — 그런데 그 사실이 판단 하나를 정했다: `revision` 이 없으므로 dispatch 는
개정 번호로 거를 수 없고, **걸러서도 안 된다.** `route.assigned` 는 계획이라 옛 것을 버려야 하지만
이 이벤트는 사실이고, 버리면 §6.8 이 읽을 값이 사라진다.

그 판단이 한 줄로 정리됐다 — **계획은 `(route, revision, seq)` 로, 사실은 `orderId` 로 식별한다.**
그래서 stop 은 `stopSeq` 로 찾지 않고, **`routeId` 로 좁히지도 않는다**: 재계획이 주문을 옮기는
동안 옛 라우트에서 끝난 배송이 도착하면 그 사실을 *지금 그 주문이 있는* stop 에 적용하고
`dawnline_status_after_relocate_total` 로 센다. 어느 라우트에도 없을 때만 철 지난 것이다.
같은 열쇠를 §5.4 의 기사 스캔 API 도 쓴다(기사는 송장을 찍지 stop 번호를 찍지 않는다).
소비 처리량은 [측정](benchmarks/phase5-delivery-status-throughput.md)에 있다(조건은 코드보다
**먼저** 적었다).

**`delivery.route-departed` 를 정한다** ([ADR-050](adr/ADR-050-route-departure-is-an-event.md),
2026-09-23, Phase 6-0b — 5-1b 이월 판정이고
[ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 재검토 지점 1 의 절반을 닫는다).

근거는 ops 화면이 아니라 **사실의 가시성**이다. 5-1b 가 출발을 *첫 편차의 출처*로 만들었다
(§5.4 `DEPARTED_CAMP` — 늦은 출발은 가장 흔한 지연 원인이고 그 편차는 첫 `ARRIVED` 전에 이미
알 수 있다). 그런데 지금 그 사실을 아는 것은 tracking 뿐이라, ops 는 첫 `ARRIVED` 가 올 때까지
**「출발 안 함」과 「출발했는데 아직 도착 없음」을 구별하지 못한다.** 그 구간이 정확히 운영자가
개입할 수 있는 마지막 창이다 — 아직 안 나간 차는 다시 짤 수 있다. 그리고
`plannedDeparture − departedAt` 은 라스트마일의 고전 KPI(출발 정시율)라, peak-day 스토리에서
「출발 지연 → at-risk → 재계획」의 첫 칸이 된다.

**「정의하지 않는다」가 더 단순하다는 것을 알고 취하지 않았다** — 그 단순함의 대가가 운영자
에게서 마지막 개입 창을 숨기는 것이기 때문이다. 그 문장이 이 결정의 근거다.

형태는 최소로 둔다. 키 `routeId`, 페이로드
`{routeId, campId, revision, plannedDeparture, departedAt}`. tracking 은 이 다섯을
**새 컬럼 없이** 낼 수 있다 — `route_revisions` 가 `camp_id`·`planned_departure` 를 들고
(§5.4, `V2`), `revision` 은 같은 행에, `departedAt` 은 스캔의 `occurredAt` 이다.
**`stopCount` 는 뺐다**(2026-09-24, [ADR-050](adr/ADR-050-route-departure-is-an-event.md) 재검토 지점 3 닫힘) —
**부재를 다른 출처로 메우지 않는다.** stop 수의 진실은 dispatch 의 계획이고 ops 는 `route.assigned`
에서 받는다. 출발이 먼저 와서 행을 만들면 그 칸은 몇 초 동안 비어 있고(ADR-051 — 화면은 「—」),
tracking 의 `COUNT(DISTINCT stop_seq)` 로 그 몇 초를 메우면 같은 사실의 둘째 출처가 된다 —
[ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 이 `route:{id}:progress` 캐시를 지운 이유와 같다.

**스키마·예시·토픽 생성·발행은 묶음 B 에서 한다.** 소비자(ops 의 `rm_routes` 프로젝션)가 먼저
정의하고 tracking 이 outbox 로 낸다 — `DEPARTED_CAMP` 처리에 이미 그 자리가 있다. 지금 이 행과
`contracts/events/` 가 갈라져 있는 것은 **의도한 짧은 간격**이고, 채워지는 순간 그 어긋남은
스스로 드러난다: `EventContractsTest` 의 `PARTITION_KEY_FIELD` 는 예시 파일에서 역으로 돌기
때문에 `delivery.route-departed` 예시가 들어오면 그 표에 칸이 없다는 이유로 실패한다. 같은
이유로 `deploy/compose` 의 토픽 목록도 그 커밋에서 함께 는다.
**발행도 들어왔다** (2026-09-24, 같은 묶음). `RecordScanService` 의 `fromCamp` 갈래가 배송을
**실제로 옮긴** 출발 스캔에만 한 건 낸다 — 단말의 재시도는 전부 `STALE` 이라 나가지 않고, 그래서
「라우트 하나에 하나」가 거기서 지켜진다. 기사가 출발을 빼먹고 도착부터 찍은 라우트는 뒤늦은
출발 스캔이 옮길 배송이 없어 나가지 않는다 — ops 는 `delivery.status` 로 「출발했다」를 알고
(`rm_routes.status`) 시각은 비워 둔다. 지어내지 않는다.

**채워졌다** (2026-09-24). 예고대로 `PARTITION_KEY_FIELD` 에 칸이 하나 늘었고, 토픽 목록 쪽에는
그 문장을 확인하는 장치가 **없었다** — 그래서 `ComposeTopicsTest` 를 더했다(compose 의 토픽 집합
= 계약 스키마의 집합, 양쪽 다 파일에서 읽는다).

**브로커로 내보내지 않던 이유는 그대로 유효하다** — `DEPARTED_CAMP` 를 stop 마다 내보내면 한
사실을 stop 수만큼 반복하는 꼴이고 order-service 는 `DISPATCHED` 로 그 구간을 이미 덮는다
(§5.4 `ScanType.isDeliveryStatus()` — 2026-09-24 에 `isPublished` 에서 이름을 좁혔다, 출발도 이제 나가기 때문이다). 그래서 새 이벤트는 **라우트 하나에 하나**다. 그 이유가 이
이벤트를 *라우트 단위*로 만든 것이지, 이벤트를 만들지 않을 이유였던 적은 없다.

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

- **발행**: 도메인 변경과 `outbox_events` INSERT를 같은 DB 트랜잭션에서 수행. 별도 릴레이(`OutboxRelay`, 폴링 100ms, 배치 500, `FOR UPDATE SKIP LOCKED`)가 Kafka로 발행 후 `published_at` 기록. SKIP LOCKED 는 다중 인스턴스에서의 중복 발행을 막지만, 같은 `partition_key` 의 행이 서로 다른 인스턴스에서 발행되면 §4.5의 키 단위 순서가 깨질 수 있다. 따라서 릴레이는 **서비스당 단일 활성 인스턴스**여야 하고, 그것을 **리더 락이 강제한다**(2026-09-05, [ADR-027](adr/ADR-027-outbox-relay-leader-lock.md)). SKIP LOCKED 는 리더 전환 경합의 안전망으로 유지한다.
- **릴레이 리더 락** (PostgreSQL advisory lock, [ADR-027](adr/ADR-027-outbox-relay-leader-lock.md) + 2026-09-05 정정): 배치 <strong>전에</strong> 매번 `pg_try_advisory_lock(classid, objid)` 로 확인하고, 리더가 아니면 발행하지 않는다. 키는 (저장소 네임스페이스, 서비스명 해시) 두 정수다. 락은 **전용 장수 세션**에 잡는다 — 세션 수준 락이라 커넥션을 풀에 반납하면 락이 반납된 커넥션에 남는다. 리더 여부는 메모리 플래그가 아니라 **매 사이클 `pg_locks` 에 물어** 도출한다(세션이 끊기면 재연결 후 재획득). **TTL 도 갱신도 없다** — 세션이 죽으면 서버가 즉시 락을 푼다. 정상 종료는 `pg_advisory_unlock_all()` 로 내려놓아 발행 공백을 폴링 주기로 만든다. **락이 거는 것은 발행뿐이다** — 메트릭 갱신과 정리는 리더가 아니어도 돈다(순서를 가진 것은 발행밖에 없다).
- **판정 불가는 팔로워가 아니다.** 상태는 `LEADER`/`FOLLOWER`/`UNKNOWN` 셋이고 게이지 `dawnline_outbox_leader` 가 `1/0/-1` 로 노출한다(§9.1). 발행을 멈추는 결정은 뒤의 둘이 같지만 봐야 할 곳이 정반대다 — 팔로워는 정상이고 판정 불가는 **DB 세션 장애**다.
- **조정자는 outbox 가 있는 바로 그 DB 다.** 그래서 "락을 못 잡아 발행을 멈춘다" 와 "DB 가 죽어 발행할 것을 읽지 못한다" 가 **같은 사건**이 되고, fail-open/fail-closed 딜레마 자체가 사라진다 — 조정자만 죽고 저장소는 살아 있는 상태가 존재하지 않기 때문이다. 릴레이 리더 선출은 **서비스 내부** 조정이고, 한 서비스의 인스턴스들은 정의상 같은 DB 를 공유한다(불변규칙 3). 이 자리에 Redis 를 두면 다섯 서비스의 발행 경로가 조정용 외부 의존 하나에 묶이는데, 그것은 불변규칙 7 의 예외가 아니라 **위반**이었다(2026-09-05 [ADR-027 후속 정정](adr/ADR-027-outbox-relay-leader-lock.md)).
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
- **캠프 좌표는 `wave.closed` 의 `depot` 스냅샷으로 들어온다**(2026-09-05 결정). 캠프는
  fulfillment 의 참조 데이터인데 라우트의 출발·복귀 지점이라 계획에 반드시 필요하고, 불변규칙 4 가
  코어 서비스 간 동기 호출을 금지한다 — 그래서 **계획을 촉발하는 이벤트가 싣는다**(`order.placed` 의
  `cutoffAt` 과 같은 논리). `camp.registered` 같은 참조 데이터 동기화 이벤트를 두지 않는 이유는
  10행짜리 데이터를 위해 초기 적재·갱신·순서라는 수명주기를 통째로 들여오게 되기 때문이다.
  좌표는 `route_plans.depot_lat/lng` 에 저장한다 — 정체 회수·운영자 재실행·§6.8 부분 재계획은
  `wave.closed` 를 다시 받지 않는다.
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
  -- promise_revised 는 우선도의 *근거*, priority 는 그 결과다 (ADR-028). 둘 다 남긴다 —
  -- 근거만 두면 점수표를 바꿀 때 계획 중인 웨이브의 우선도가 흔들리고(§6.3 스냅샷),
  -- 결과만 두면 "왜 이 우선도인가" 에 답할 수 없다.
  promise_revised BOOLEAN NOT NULL DEFAULT FALSE,
  priority SMALLINT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0);
CREATE INDEX ix_cand_wave ON dispatch_candidates (wave_id, status);
CREATE TABLE route_plans (id UUID PK, wave_id UUID NOT NULL UNIQUE, camp_id UUID NOT NULL, status VARCHAR(16) NOT NULL,
  strategy VARCHAR(32), mode VARCHAR(8), mode_reason VARCHAR(16), -- 왜 그 모드였나 (§6.7, ADR-034)
  seed BIGINT, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
  total_cost_krw BIGINT, assigned_count INTEGER, unassigned_count INTEGER, plan_duration_ms INTEGER, version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE routes (id UUID PK, plan_id UUID REFERENCES route_plans, vehicle_id UUID, driver_id UUID, seq_no SMALLINT,
  status VARCHAR(16), revision INTEGER NOT NULL DEFAULT 1, stop_count INTEGER, distance_m INTEGER, duration_s INTEGER,
  cost_krw INTEGER, planned_departure TIMESTAMPTZ,       -- 캠프 출발 계획 시각 (V7, Phase 5-1b)
  -- §6.8 재계획 쿨다운(라우트당 10분). 재계획 트랜잭션 안에서 비교·갱신한다 (V10, ADR-046 결정 3).
  -- tracking 의 Redis 쿨다운은 알림 수를 지키지 정확성을 지키지 않는다 — 두 at-risk 는 eventId 가
  -- 달라 processed_events 가 막지 못한다. NULL 이면 아직 재계획한 적이 없다.
  last_replanned_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE route_stops (id UUID PK, route_id UUID REFERENCES routes, seq SMALLINT NOT NULL, lat NUMERIC(9,6), lng NUMERIC(9,6),
  planned_arrival TIMESTAMPTZ, planned_departure TIMESTAMPTZ, service_s INTEGER,
  -- PLANNED | CANCELLED | ARRIVED | COMPLETED | FAILED. 뒤의 셋은 delivery.status 소비가 옮긴다
  -- (V8 주석 정정, Phase 5-5, ADR-047). CHECK 제약은 두지 않는다 — §4.7 이 같은 major 안에서
  -- enum 값 추가를 허용하므로, 제약을 걸면 값이 하나 늘 때마다 마이그레이션이 필요해진다.
  status VARCHAR(16),
  promised_start TIMESTAMPTZ, promised_end TIMESTAMPTZ,  -- 이 stop 의 약속창 (V6, Phase 5-1a)
  -- 그 stop 에 **처음 닿은** 시각 (V10, Phase 5-3, ADR-048 결정 1). delivery.status 의 occurredAt 을
  -- 5-5 의 전이가 함께 적는다. ARRIVED|COMPLETED|FAILED 중 먼저 온 것이 쓰고 덮어쓰지 않는다 —
  -- 덮으면 이 값은 도착이 아니라 완료가 되고, §6.8 의 편차가 체류 시간까지 더한 값으로 바뀐다.
  -- NULL 이면 아직 닿지 않았다(= §6.8 이 다시 푸는 대상). 편차를 모르는 것과 0 은 다르다.
  actual_at TIMESTAMPTZ,
  UNIQUE (route_id, seq));
CREATE TABLE route_stop_orders (stop_id UUID REFERENCES route_stops, order_id UUID, PRIMARY KEY (stop_id, order_id));
-- 사실은 orderId 로 식별한다 (V9, Phase 5-5, ADR-047 결정 2). PK 의 선두 컬럼이 stop_id 라
-- order_id 단독 조회가 그 인덱스를 못 쓴다. delivery.status 는 stop 방문마다 이 조회를 한다.
CREATE INDEX ix_rso_order ON route_stop_orders (order_id);
CREATE TABLE dispatch_rules (id UUID PK, camp_id UUID NULL, name VARCHAR(64) NOT NULL, type VARCHAR(48) NOT NULL,
  severity VARCHAR(8) NOT NULL CHECK (severity IN ('HARD','SOFT')), params JSONB NOT NULL, priority SMALLINT NOT NULL,
  enabled BOOLEAN NOT NULL, rule_version INTEGER NOT NULL, updated_at TIMESTAMPTZ);
CREATE TABLE plan_explanations (id UUID PK, plan_id UUID NOT NULL, order_id UUID, route_id UUID, rule_name VARCHAR(64),
  outcome VARCHAR(16), detail JSONB);
CREATE INDEX ix_expl_plan_order ON plan_explanations (plan_id, order_id);
```

**`route_stops` 의 약속창은 발행이 요구한 컬럼이다** (2026-09-18, Phase 5-1a). `route.assigned.v1`
의 stop 은 `promisedWindow` 를 **required** 로 싣는다 — tracking 이 at-risk 를 판정하려면 필요하고
(§5.4), 불변규칙 4 에 따라 그 이벤트가 유일한 정보원이다. 최초 발행은 계획 결과(`PlannedStop` →
`Stop.promised()`)에서 값이 나오지만, **§6.10 의 개정 발행은 저장된 라우트에서 만든다** — 취소된
stop 이 `PlannedRoute` 에는 없기 때문이다([ADR-026](adr/ADR-026-dispatch-cancellation-window.md)
결정 4). 그래서 그 경로에는 DB 가 값의 출처여야 한다.

> **버린 대안: 발행 시점에 `dispatch_candidates` 를 조인해 창을 끌어온다.** 컬럼이 늘지 않지만
> 출처가 *다른 애그리거트의 보존 정책*에 매달린다 — 후보 행이 정리되면 개정 발행이 조용히
> required 필드를 잃는다. 그리고 「이 stop 의 약속창」은 계획이 정한 사실이지 후보 테이블의
> 파생이 아니다: `StopMerger` 의 병합 키가 「같은 약속창」이므로(§6.5 1단계) 그 값은 stop 이
> 만들어지는 순간 확정된다.

**Redis**: `rules:camp:{id}:v{n}` (룰셋 캐시).

`route:{id}:progress` 는 **설계에서 뺐다**(2026-09-23, Phase 6-0c — §7.2 표 아래에 근거가 있다). 5-5 가 채우던 키인데 읽는 쪽이 끝내 나타나지 않았고, `GET /routes/{routeId}` 가 stop 마다 살아 있는 상태를 이미 돌려주므로 그 캐시를 읽는 것은 같은 사실의 둘째 출처를 만드는 일이었다.

`lock:plan:{waveId}` 는 **설계에서 뺐다**(2026-09-05). "이중 안전장치" 라고 적혀 있었지만 `route_plans.wave_id` 의 UNIQUE 제약이 이미 그 안전장치이고, 계획 유스케이스는 그 제약 위에서 `openPlan` 이 경합을 흡수하도록 짜여 있다(§5.3 `RunPlanService`). 두 번째 장치는 없는 문제를 막으면서 Redis 장애 시 무엇이 맞는지를 새로 정하게 만든다 — **폴백을 정해야 하는 키를 하나 늘리는 것이 안전장치를 하나 늘리는 것보다 비싸다.**

### 5.4 tracking-service

**책임**: 라우트별 배송 진행, ETA, 지연 위험 감지, 상태 통지.

- `route.assigned` 수신 → stop마다 `shipments` 생성(status `SCHEDULED`, `eta_at = planned_arrival`, `promised_end = promisedWindow.end`).
- 기사 스캔 API `POST /api/v1/routes/{id}/stops/{seq}/events` (DEPARTED_CAMP, ARRIVED, COMPLETED, FAILED, 위치 포함). 시뮬레이터가 호출. **본문의 `orderIds` 가 열쇠이고 경로의 `{id}`·`{seq}` 는 확인용이다**(바로 아래).
- **스캔은 주문으로 푼다 — 번호는 확인용이다** (2026-09-23, [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 1). 본문의 `orderIds` 가 **required** 이고 tracking 은 그것을 `shipments.order_id`(PK)로 푼다. 경로의 `{id}`·`{seq}` 는 조회 조건이 아니라 **확인용 컨텍스트**다 — 기사는 개정 r 의 번호로 찍는데 tracking 은 이미 r+1 을 적용했을 수 있고, 그때 `(route, seq)` 는 다른 주문을 가리키거나 아무것도 가리키지 않는다. 번호로 찾으면 앞의 경우는 **엉뚱한 주문을 배송 완료로 적고** 뒤의 경우는 404 다 — 둘 다 기사가 고칠 수 없다. **현실의 배송 스캔이 stop 번호가 아니라 송장(주문)을 찍는 것**이 이 열쇠의 근거이고, 모호성이 없는 쪽을 찍는 것이다.
  찍은 자리와 tracking 이 아는 자리가 다르면 **그대로 적용하고** `dawnline_scan_after_relocate_total` 로 센다(§9.1) — 그 값이 개정과 기사가 어긋난 창의 크기이고, dispatch 의 `dawnline_status_after_relocate_total` 과 한 쌍이다. 편차 전파와 `delivery.status` 발행도 **배송이 지금 있는 (라우트, 순번)** 에서 한다: 요청이 말한 좌표로 전파하면 개정이 옮긴 stop 의 ETA 를 엉뚱하게 밀고, 옛 좌표를 그대로 실어 보내면 dispatch 의 확인용 컨텍스트가 틀린 값을 받아 저쪽 카운터가 우리 탓으로 오른다. 그래서 **한 스캔이 `delivery.status` 두 건이 될 수 있다** — 그 주문들이 지금 서로 다른 stop 에 있으면 그것은 두 지점의 사실이다.
  `DEPARTED_CAMP` 만 예외다: **라우트의 사건**이라 `orderIds` 를 싣지 않고(실으면 400) 그 라우트 전체에 적용한다. 사유를 `FAILED` 에만 붙이는 것과 같은 모양이다 — 종류가 필드의 뜻을 정하고, 어긋나면 조용히 버리지 않고 거절한다.
- ETA 재계산: 현재 stop 실제 시각 − 계획 시각 = 편차 `d`. 이후 stop들의 `eta = planned + d` (단순 이동 모델; 개선 여지는 §17). 부호를 지우지 않는다 — 일찍 도착하면 음수로 당겨진다. 「늦은 것만 민다」로 적으면 앞서 가는 라우트의 ETA 가 낡은 채로 남고 ops 화면이 그 값을 읽는다.
- **`DEPARTED_CAMP` 는 라우트의 사건이다** (Phase 5-1b). 경로의 `{seq}` 를 무시하고 그 라우트의 배송 <em>전부</em>를 `OUT_FOR_DELIVERY` 로 옮긴다 — 기사는 캠프를 한 번 떠나고, 그 순간 모든 배송이 길 위에 있다. stop 하나만 옮기면 나머지는 `SCHEDULED` 로 남아 「아직 출발하지 않은 배송」처럼 보인다. 그리고 이 갈래가 **첫 편차의 출처**다: 기준값은 `route_revisions.planned_departure`(= `route.assigned.v1` 의 `summary.plannedDeparture`, required)이고, 늦은 출발은 가장 흔한 지연 원인이면서 **첫 `ARRIVED` 스캔 전에 이미 알 수 있다.** 브로커로는 나가지 않는다 — 한 사실을 stop 수만큼 반복해 말하는 것이고 order-service 의 상태 머신은 `DISPATCHED` 로 그 구간을 이미 덮는다(`ScanType.isPublished()`). 운영자가 출발 사실을 화면에서 원하면 라우트 단위 이벤트 하나(`delivery.route-departed`, 키 `routeId`)를 **첫 소비자가 나타나는 Phase 6 에서 소비자 주도로** 정한다. **정했다** ([ADR-050](adr/ADR-050-route-departure-is-an-event.md), 2026-09-23, Phase 6-0b — §4.1 표와 그 아래 문단). 근거는 화면이 아니라 이 갈래가 *첫 편차의 출처*라는 것이다: 그 편차를 아는 것이 tracking 뿐이면 ops 는 첫 `ARRIVED` 까지 「출발 안 함」과 「출발했는데 아직 도착 없음」을 구별하지 못하고, 그 구간이 운영자가 개입할 수 있는 마지막 창이다. **발행이 이 자리에 붙었다**(2026-09-24) — 배송을 실제로 옮긴 출발 스캔에만, 라우트에 한 건.
- **편차 전파는 애그리거트 밖이다** (`EtaPropagator`). 편차는 <em>라우트</em>의 성질이다 — 어느 stop 에서 얼마가 벌어졌고 그것이 누구에게 옮겨 가는지는 방문 순서를 아는 쪽만 안다. `Shipment` 는 주문 하나만 알고, 받는 것은 결과값 하나(`projectEta`)다. 종결 상태를 옮기지 않는 판단만 애그리거트의 것이다 — 「어디서 움직이는가」의 답이 하나여야 한다.
- **at-risk 규칙**: 어떤 stop의 `eta > promised_end − 15분`이면 `delivery.at-risk` 1회 발행(라우트당 5분 쿨다운, Redis `SET NX`). 페이로드에 남은 stop 목록·편차 포함.
  **이것은 사건이지 상태가 아니다**([ADR-046](adr/ADR-046-at-risk-is-an-event.md)). 위험이 계속되면 다시 알리고(쿨다운이 그 주기다) **사라지는 경우는 알리지 않는다** — dispatch 가 이미 시작한 재계획을 취소할 방법이 없고, 해소된 ETA 는 ops 의 읽기 모델(§5.5)이 그대로 보여 준다. 소비자는 「위험 해제」를 기다리지 않는다.
  페이로드의 `remainingStops` 에는 **위험한 stop 만이 아니라 남은 전부**가 들어간다 — §6.8 이 다시 푸는 대상은 남은 구간이다. stop 마다 `atRisk` 를 함께 싣는 이유는 여유(15분)가 tracking 의 정책이기 때문이다: 소비자가 다시 계산하면 두 곳이 갈라진다. `campId` 도 싣는다 — dispatch 는 자기 `routes` 로 알 수 있지만 ops 는 이 이벤트만 본다(불변규칙 4).
  **쿨다운이 지키는 것은 알림 수이지 정확성이 아니다.** Redis 가 죽으면 쿨다운 없이 발행하고(fail-open, `dawnline_at_risk_cooldown_bypassed_total`), 중복이 *재계획 두 번*이 되지 않게 하는 것은 dispatch 의 DB 쿨다운이다(§6.8 `routes.last_replanned_at`). 멱등 소비자는 막지 못한다 — 두 at-risk 는 `eventId` 가 다르다.
- 상태 머신: `SCHEDULED → OUT_FOR_DELIVERY → ARRIVED → COMPLETED | FAILED`, 그리고
  `SCHEDULED`·`OUT_FOR_DELIVERY` → `CANCELLED`. 축 규칙의 **셋째 자리**다(order·fulfillment 와
  같은 규칙, 다른 자리 — §13): 역행은 무시하고, 미래 상태로의 건너뜀은 받아들이며
  (`SCHEDULED` 에 `COMPLETED` 가 오면 완료다 — 도착 스캔을 기사가 빼먹은 것이지 배송이
  안 된 것이 아니다), **`CANCELLED` 뒤에 오는 스캔은 무시하되 센다**
  (`dawnline_scan_after_cancel_total`, §9.1). 마지막 하나는 기사가 취소를 못 받고 배송한
  경우이고 dispatch 의 `dawnline_cancel_too_late_total`(§6.10)과 한 쌍이다 — 저쪽은 「배송이
  끝난 주문에 취소가 왔다」, 이쪽은 「취소된 주문이 배송됐다」 를 센다.

```sql
CREATE TABLE shipments (order_id UUID PK, route_id UUID NOT NULL, stop_seq SMALLINT NOT NULL, status VARCHAR(20) NOT NULL,
  planned_arrival TIMESTAMPTZ NOT NULL, eta_at TIMESTAMPTZ NOT NULL, promised_end TIMESTAMPTZ NOT NULL,
  delivered_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0);
CREATE INDEX ix_ship_route ON shipments (route_id, stop_seq);
-- 개정 비교의 자리 (§8.5 의 「routeId + revision」). 라우트당 한 행.
CREATE TABLE route_revisions (route_id UUID PK, revision INTEGER NOT NULL CHECK (revision >= 1), camp_id UUID NOT NULL, planned_departure TIMESTAMPTZ NOT NULL, applied_at TIMESTAMPTZ NOT NULL);
CREATE TABLE shipment_events (id UUID, order_id UUID, route_id UUID, type VARCHAR(20), occurred_at TIMESTAMPTZ NOT NULL,
  lat NUMERIC(9,6), lng NUMERIC(9,6), payload JSONB, PRIMARY KEY (occurred_at, id)) PARTITION BY RANGE (occurred_at);
-- 일 단위 파티션, 보존 30일 (pg_partman 없이 Flyway + 스케줄러로 생성/삭제)
```

**세 칸이 `NOT NULL` 인 것은 계약이 정했다.** `planned_arrival`·`eta_at`·`promised_end` 의 출처는
`route.assigned.v1` 의 `plannedArrival` 과 `promisedWindow` 둘뿐이고 둘 다 **required** 다(Phase 5-1a
계약). NULL 이 들어올 경로가 없는 칸을 NULL 허용으로 두면 at-risk 판정에 「창을 모르는 stop」
분기가 생기고, 그 분기는 한 번도 실행되지 않으면서 리뷰마다 읽힌다. `promisedWindow` 를 `required` 로 넣은 근거는
`contracts/events/README.md` §5 의 **예외 표**에 있다(생산자 하나 · 같은 커밋 · 운영 소비자 0,
2026-09-18) — 그 표가 이 `NOT NULL` 의 기록이다.

**개정 비교는 라우트 단위다.** §8.5 는 `route.assigned` 소비의 멱등 키를 「routeId + revision」
으로 적었고, 그 비교는 **라우트당 마지막으로 적용한 개정**을 알아야 성립한다. 그래서
`route_revisions` 에 라우트당 한 줄을 둔다([ADR-045](adr/ADR-045-revision-comparison-is-per-route.md)). 버린 대안 둘:

> **(1) `shipments` 에 `route_revision` 컬럼을 두고 `MAX(...) WHERE route_id = ?` 로 유도.**
> §6.8 의 `relocate` 가 한 라우트의 미완료 stop 을 전부 다른 라우트로 옮기면 그 라우트에
> 행이 남지 않는다. 그 상태에서 예전 개정이 DLQ replay 로 돌아오면 비교할 값이 없어
> **이미 옮겨간 주문들이 되돌아온다.**
>
> **(2) shipment 행마다 비교.** 개정 번호는 라우트마다 독립이라(A 가 5, B 가 1) 주문이
> A→B 로 옮겨갈 때 정당한 이벤트가 「낮은 번호」로 보여 버려진다. 번호를 라우트 밖에서
> 비교하는 순간 그 번호는 순서를 뜻하지 않는다.

비교와 기록은 **한 문장**이다(`ON CONFLICT … DO UPDATE … WHERE revision < EXCLUDED.revision`).
읽고-비교하고-쓰면 그 사이가 창이 되고, 같은 라우트의 두 개정이 동시에 들어올 때 둘 다 자기가
최신이라고 읽는다. 버려진 개정은 `dawnline_event_stale_total{eventType="route.assigned"}`(§9.1)
로 센다 — 거부가 아니라 순서 역전 흡수이므로 DLQ 로 보내지 않는다.

**그리고 이 개정에 <em>없는</em> 배송은 건드리지 않는다.** §6.8 의 `relocate` 가 주문을 A → B 로
옮기면 A 의 개정(그 주문이 빠진)과 B 의 개정(그 주문이 실린)은 서로 다른 파티션으로 나가 순서가
없다. 「이 라우트의 shipment 중 개정에 없는 것을 정리」하는 한 줄은 A 의 개정이 먼저 처리될 때
아직 옮겨가지 않은 그 주문을 죽이고, 뒤에 온 B 의 개정은 종결 상태를 만나 아무것도 못 한다 —
**이동이 영영 사라진다.** 부재는 값이 아니다(ADR-026); 여기서는 그것이 경합 방어선이고, 두
도착 순서를 각각 보는 IT 둘이 지킨다.

**개정은 종결 상태를 되돌리지 않는다 — 되돌릴 것이 있어서가 아니라 갱신할 것이 없어서다.**
새 개정이 오면 `COMPLETED`·`FAILED`·`CANCELLED` 인 shipment 는 그대로 두고, 나머지만
`route_id`·`stop_seq`·`planned_arrival`·`eta_at`·`promised_end` 를 갱신한다. 앞의 둘은 §6.8 의
부분 재계획이 완료 stop 을 고정하는 것과 같은 규칙이지만, tracking 은 그것을 **페이로드가 아니라
자기 규칙으로** 지킨다 — Phase 5-5 전에는 dispatch 가 진행 상황을 모르므로 이 규칙이 tracking
쪽의 **유일한 방어선**이다. `CANCELLED` 가 함께 들어가는 이유는 다르다: 취소된 배송의 계획 도착
시각을 옮기는 일은 아무 물음에도 답하지 않는다. 그래서 조건은 「완료했는가」가 아니라
**「종결인가」**(`ShipmentStatus.isTerminal()`)이고, 셋이 한 줄로 걸린다.

**스캔 API 는 멱등 키를 요구하지 않는다 — 멱등을 상태 머신이 만들기 때문이다.** §8.5 의 키는
「`(orderIds, type)` + 상태 머신」이고(2026-09-23 에 `(routeId, seq, type)` 에서 바뀌었다 —
번호는 개정본의 좌표라 재시도 사이에 뜻이 변한다. 주문 id 는 변하지 않는다), 같은 스캔이
다시 오면 이미 지나온 지점이라 `STALE` 로
흡수된다. 별도의 키를 요구하면 단말이 그것을 재시도 사이에 보존해야 하는데, 오프라인에서 다시
켜지는 기기에 그것은 쉬운 요구가 아니다. 같은 이유로 **취소 뒤의 스캔도 200** 이다 — 기사가
취소를 받지 못하고 배송한 경우이고 기사가 고칠 수 있는 문제가 아니다. 오류로 답하면 단말이
재시도를 반복하고 그동안 다음 stop 이 밀린다. 응답의 해당 주문 줄이 `AFTER_CANCEL` 이고, 세는
것은 `dawnline_scan_after_cancel_total` 이다. 한 스캔이 <em>주문마다</em> 다른 답을 낼 수 있다 —
통합된 stop 에서 하나만 취소된 경우가 그것이다(ADR-026 후속 정정).

**`shipment_events` 에는 상태를 옮긴 스캔만 남는다.** `STALE` 과 `AFTER_CANCEL` 은 행이 되지
않는다. 남기면 이 로그를 읽는 사람이 「어느 행이 실제로 무언가를 바꿨나」를 알기 위해 상태 머신을
다시 구현해야 한다. 취소 뒤 스캔의 기록은 위 카운터이고(§5.4 「무시하되 센다」), 중복 스캔은
기록할 값이 없다. `occurredAt` 은 **required** 이고 기본값이 없다 — 빠뜨린 요청이 조용히 「지금」이
되면 정시율(§8.1)이 어긋난 이유를 아무도 찾을 수 없다. `failureReason` 은 `payload` JSONB 에
`{"failureReason": …}` 로 들어가고, 사유가 없으면 `payload` 는 `NULL` 이다 —
`{"failureReason": null}` 은 「칸이 있는데 비어 있다」로 읽힌다. 그 JSON 은 **PostgreSQL 이**
`jsonb_build_object` 로 만든다: 기사가 쓴 자유 텍스트의 이스케이프를 자바에서 손으로 하면 틀린
날 깨지는 것이 행 하나가 아니라 배치 전체다.

**파티션은 함수 하나가 만들고 스케줄러가 부른다.** `shipment_events` 에 **DEFAULT 파티션을 두지
않는다.** 두면 범위 밖 행이 조용히 거기 쌓이고, 나중에 그 날짜의 파티션을 만들 때
PostgreSQL 이 DEFAULT 를 스캔해 겹치는 행을 발견하고 **그때** 실패한다 — 생성이 멈췄다는 사실이
며칠 뒤 다른 얼굴로 나타난다. 파티션이 없으면 INSERT 가 그 자리에서 실패하고
(`no partition of relation "shipment_events" found for row`) 원인이 곧 메시지다.

이름 규칙은 마이그레이션의 함수 둘(`tracking_ensure_event_partitions(from, days)` ·
`tracking_drop_event_partitions(before)`)에만 있다. 스케줄러는 **주입된 시계에서 뽑은 날짜**를
넘길 뿐이다(불변규칙 12) — 이름을 자바에서도 만들면 규칙이 두 곳이 되고 둘은 갈라진다.
마이그레이션은 기동 직후에도 쓸 수 있도록 `CURRENT_DATE − 1` 부터 9일치를 미리 만든다(빈
파티션뿐이다).

**경계는 UTC 자정이다 — 그리고 영업일은 KST 다.** `occurred_at` 은 `TIMESTAMPTZ` 라 물리적으로
UTC 로 저장되고 UTC 에는 서머타임이 없다 — 경계가 항상 24시간이고, 같은 스크립트가
환경마다 같은 날을 가른다. 그런데 이 시스템의 영업일은 KST 다 — 컷오프도 웨이브도 전부
(§5.2, 부록 A). 따라서 **「오늘(KST) 의 스캔」은 언제나 UTC 파티션 둘에 걸친다**(KST 00:00–09:00 은
전날 UTC). 정확성에는 무관하고 조사 질의의 가지치기가 하나 대신 둘이 될 뿐이라 그대로
수용한다. **이 문단이 있는 이유는 나중에 누가 이것을 「고치지」 않게 하려는 것이다** —
KST 경계로 바꾸면 서머타임이 없는 지금은 괜찮아 보이지만, 경계가 세션 존에 따라 움직이면
마이그레이션을 돌리는 자리마다 다른 날이 만들어진다(그 음성 표본은 §13 아홉째 축에 있다).

생성이 멈춘 것은 조용하면 안 된다. `dawnline_shipment_partitions_ahead`(§9.1)가 **오늘을 포함해
앞으로 덮여 있는 날 수**를 재고, 스케줄러가 죽으면 이 값이 날마다 1씩 줄다가 0 에서 INSERT 가
실패한다. 알림은 2 에서 걸린다(§9.4) — 막히기 하루 전에 사람이 본다.

**원 약속 대비 정시율은 여기서 내지 않는다.** tracking 의 `promised_end` 는 `route.assigned` 가 준
값 하나이고, 그것이 원래 약속인지 개정된 약속인지 이 서비스는 모른다(§9.1 의 같은 문단).
두 기준은 **ops-api 의 읽기 모델**이 `order.placed`(원 약속)과 `delivery.status`(완료 시각)을
이어서 낸다 — §5.5 의 결정이다.

**Redis**: `driver:{id}:pos` (GEO), `route:{id}:atrisk:cooldown`.

### 5.5 ops-api + ops-web (백오피스)

**ops-api**
- 모든 토픽을 구독해 **읽기 모델**을 갱신 (CQRS 프로젝션). 코어 서비스 DB는 절대 직접 읽지 않는다.
- 커맨드는 코어 서비스 REST로 위임: 웨이브 조기 마감, 계획 재실행, stop 재배정, 주문 홀드/취소, DLQ 재처리, **outbox 격리 행 조회·재큐**(§4.6 발행 측 실패).
  (2026-09-24, 묶음 B) 들어온 것은 코어에 엔드포인트와 계약 문서가 **이미 있는** 셋이다 — 아래 「커맨드 위임」.
  **웨이브 조기 마감**·**outbox 격리 조회·재큐**는 작업 2(fulfillment 의 첫 운영 엔드포인트와 그 OpenAPI 문서)
  뒤에 같은 경로로 붙는다. **DLQ 재처리**는 ops-api 가 직접 하는 일이라 위임과 모양이 달라 따로 붙는다.
  **주문 홀드는 미구현 — 전이 없음**: order-service 의 상태 머신(§5.1)에 홀드 전이가 없다. 목록에서 지우지
  않고 이 표시로 남긴다 — 지우면 「검토했는데 없는 것」과 「잊은 것」을 구별할 수 없다.
- 인증: JWT(HS256, 로컬 시크릿), 역할 `OPS_VIEWER`, `OPS_OPERATOR`, `ADMIN`. 커맨드는 `OPS_OPERATOR` 이상. 모든 커맨드는 `audit_logs`에 기록.
  (2026-09-24, [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md) 결정 2)
  **발급은 스크립트, ops-api 는 검증만**: `make token ROLE=…` 가 로컬 시크릿(`DAWNLINE_OPS_JWT_SECRET`)으로
  HS256 토큰을 찍고 **만료는 12시간**이다(데모 토큰이 저장소나 스크린샷에 남았을 때의 반경). 사용자 저장소가
  설계에 없으므로 로그인 엔드포인트를 두지 않는다 — 개발 프로필 한정이어도 「프로필이 꺼져 있다」는 조용한
  전제가 하나 는다. 역할은 클레임 `roles` 이고 계층이다(`ADMIN` ⊃ `OPS_OPERATOR` ⊃ `OPS_VIEWER`). `GET` 은
  `OPS_VIEWER`, 나머지 메서드는 `OPS_OPERATOR`. 시크릿이 없거나 32바이트(256비트)보다 짧으면 **기동하지
  않는다** — 열린 채로 뜨는 것보다 뜨지 않는 것이 낫다. ops-web 은 토큰을 붙여 넣는 설정 화면 하나다.

**커맨드 위임** (2026-09-24, 묶음 B, [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md)).
위임 클라이언트는 **커밋된 `contracts/openapi/*.yaml` 에서 빌드 때 생성한다** — 문서가 바뀌면 컴파일이 깨진다.
ops-api 가 §11 「문서가 계약이다」의 첫 소비자다. 경로는 코어의 것을 그대로 쓴다(ops-api 가 코어 앞에 선 유일한
표면이라 이름을 바꿀 이유가 없다).

| ops-api | 위임 대상 | `audit_logs.action` · `target_type` |
|---|---|---|
| `POST /api/v1/plans/{waveId}/run` (`campId`·`strategy`·`mode`) | dispatch 같은 경로 | `RUN_PLAN` · `WAVE` |
| `POST /api/v1/routes/{routeId}/stops/{orderId}/reassign` | dispatch 같은 경로 | `REASSIGN_STOP` · `ORDER` |
| `POST /api/v1/orders/{orderId}/cancel` | order 같은 경로 | `CANCEL_ORDER` · `ORDER` |

- **감사 행은 위임 _전에_ 쓴다.** 별도 트랜잭션으로 `PENDING` 을 커밋한 뒤에 코어를 부른다 — 순서가 반대면
  위임과 기록 사이에 죽었을 때 기록이 사라진다. 기록을 쓰지 못하면 **위임하지 않는다**(503).
- **결과는 넷이고 모름을 값으로 접지 않는다.** `SUCCEEDED`(2xx) · `REJECTED`(코어의 4xx — 적용되지 않았다) ·
  `FAILED`(**연결이 맺어지지 않았다** — 요청이 코어에 닿지 않은 것이 확실한 유일한 경우) · `UNKNOWN`(그 밖의
  전부: 응답 전 타임아웃, 응답 도중 끊김, **코어의 5xx**). 5xx 를 `FAILED` 로 두지 않는 이유: 5xx 는 「무언가
  깨졌다」이지 「아무 일도 없었다」가 아니다 — 커밋 뒤 직렬화에서 난 예외도 500 이다.
- **응답**: `SUCCEEDED` 는 코어의 본문, `REJECTED` 는 **코어의 상태와 Problem Details 본문을 바이트 그대로**
  (ops-api 는 이 자리에서 프록시다 — 운영자는 코어가 말한 것을 그대로 보고, 코어가 확장 멤버를 더해도 여기서
  잘리지 않는다. 문서가 참이어야 한다는 것과 클라이언트가 그것으로 파싱해야 한다는 것은 다른 문장이다),
  `FAILED` 는 502 `core-unreachable`,
  `UNKNOWN` 은 타임아웃이면 504 `core-timeout`, 코어의 5xx 면 502 `core-error`. 어느 경우든 응답 헤더
  `X-Dawnline-Audit-Id` 에 감사 행 id 가 온다.
- **상관 헤더**: 같은 id 를 코어 호출에 `X-Dawnline-Audit-Id` 로 싣고, 코어는 그것을 MDC `auditId` 로 남긴다
  (§9.3). `UNKNOWN` 행을 사람이 해소할 때 어디를 볼지가 그 id 로 정해진다(RB-07).
- **`request` JSONB 에는 커맨드의 인자만** 넣는다(경로 변수·쿼리·본문). 코어의 응답 본문은 넣지 않는다 —
  취소 응답의 `OrderView` 는 주소 전체를 싣는다(§10 「읽기 모델에는 주소 전체를 저장하지 않음」).
- **타임아웃**: 연결 1초. 읽기는 dispatch 60초(계획 시간 p95 경보가 45초다, §9.4 — 그보다 짧으면 정상적인
  재계획이 `UNKNOWN` 이 된다), order 5초.

```sql
CREATE TABLE rm_orders (order_id UUID PK, customer_id UUID, service_tier VARCHAR(16),
  order_status VARCHAR(16), delivery_outcome VARCHAR(16),   -- 두 출처의 사실, 두 칸 (2026-09-24 정정, 아래)
  camp_id UUID, wave_id UUID, route_id UUID,
  promised_end_original TIMESTAMPTZ, promised_end_revised TIMESTAMPTZ,
  planned_arrival TIMESTAMPTZ, planned_as_of TIMESTAMPTZ,   -- route.assigned — 계획, 언제나
  eta_at TIMESTAMPTZ, eta_as_of TIMESTAMPTZ,                -- delivery.at-risk — 개정됐을 때만
  delivered_at TIMESTAMPTZ, on_time_promised BOOLEAN, on_time_revised BOOLEAN,   -- on_time_* 은 생성 칸
  updated_at TIMESTAMPTZ,
  placed_at TIMESTAMPTZ, failed_at TIMESTAMPTZ,              -- KPI 두 축의 시각 (V2, 2026-09-24, 아래)
  CHECK (NOT (delivered_at IS NOT NULL AND failed_at IS NOT NULL)));   -- 결과의 두 시각은 배타
CREATE TABLE rm_waves (wave_id UUID PK, camp_id UUID, service_tier VARCHAR(16), cutoff_at TIMESTAMPTZ, status VARCHAR(16),
  order_count INTEGER, plan_id UUID, plan_duration_ms INTEGER, total_cost_krw BIGINT, unassigned_count INTEGER,
  route_count INTEGER);   -- plan.completed 의 routeCount = 기다려야 하는 route.assigned 수 (ADR-024 · ADR-051)
CREATE TABLE rm_routes (route_id UUID PK, plan_id UUID, camp_id UUID, vehicle_id UUID, driver_id UUID,
  revision INTEGER, status VARCHAR(16), planned_departure TIMESTAMPTZ, departed_at TIMESTAMPTZ,   -- 2026-09-24
  stop_count INTEGER, completed_count INTEGER, failed_count INTEGER, at_risk BOOLEAN, distance_m INTEGER, cost_krw INTEGER);
CREATE INDEX ix_rmo_route ON rm_orders (route_id);   -- 라우트 개수 재집계 (ADR-051 결정 4)
CREATE INDEX ix_rmo_wave  ON rm_orders (wave_id);    -- 웨이브 개수 재집계
-- KPI 는 표가 아니라 rm_orders 위의 뷰 둘이다 (2026-09-24, 아래 「KPI — 두 축, 뷰」). V1 의 rm_kpi_hourly 는 V2 가 지웠다.
CREATE VIEW kpi_intake_hourly   AS …  -- (camp_id, bucket_hour = date_trunc('hour', placed_at, 'UTC')) → orders, unserviceable
CREATE VIEW kpi_delivery_hourly AS …  -- (camp_id, bucket_hour = date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC'))
                                      --   → delivered, failed, on_time_promised, on_time_revised, revised,
                                      --     outcome_without_promise (모집단에서 빠진 수)
CREATE INDEX ix_rmo_delivery_hour ON rm_orders (camp_id, date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC'));
CREATE INDEX ix_rmo_intake_hour   ON rm_orders (camp_id, date_trunc('hour', placed_at, 'UTC'));
CREATE TABLE audit_logs (id UUID PK, actor VARCHAR(64), action VARCHAR(48), target_type VARCHAR(24), target_id UUID,
  request JSONB, result VARCHAR(16), created_at TIMESTAMPTZ);
```

**결정 — `rm_orders` 는 약속을 <em>두 개</em> 든다** (2026-09-19, Phase 5-1a 에서 메모를
결정으로 올렸다). §8.1 의 정시율은 "고객이 처음 받은 약속" 기준으로 재는데,
order-service 의 `promised_start/end` 는 개정 경로에서 **덮인다**
([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 결정 3 — 덮는 것이 맞다,
고객에게 보여 줄 값은 지금 유효한 약속이다). 그러면 원 약속을 아는 곳은 `order.placed` 이벤트뿐이고,
그것을 보관해 두 기준을 모두 낼 수 있는 곳은 **여기**다. `promised_end` 한 칸으로는
`dawnline_delivery_on_time_ratio{basis}`(§9.1)의 두 값을 낼 수 없다 — 그 SLO 는 개정으로 정시율을
세탁할 수 없게 하려고 두 값으로 낸 것인데, 한 칸만 두면 정확히 그 세탁이 가능해진다.
Phase 2-7 에서 order-service 쪽을 구현하며 드러났고, Phase 5-1a 에서 tracking 의 `promised_end` 가
「개정 여부를 모르는 한 칸」이라는 것이 다시 확인되어 결정으로 올렸다.

위 DDL 의 네 칸이 두 기준을 든다 — `promised_end_original` 은 `order.placed` 가 준 원본이고
`promised_end_revised` 는 `fulfillment.planned` 의 `promiseRevised` 가 덮은 값이다(개정이 없으면
둘은 같다). 완료 시각은 `delivery.status` 의 `occurredAt` 이고, 그 둘을 이은 결과가
`on_time_promised`·`on_time_revised` 다. `basis` 라벨의 `promised`·`revised` 와 이름을 맞춰 둔다 —
메트릭과 컬럼 이름이 어긋나면 「어느 칸이 어느 라벨인가」를 읽는 사람이 매번 다시 맞춰야 한다.
**tracking 은 이 계산을 하지 않는다**(§5.4) — 알려면 fulfillment 의 데이터를 끌어와야 하고
그것이 불변규칙 4 가 막는 것이다.

`rm_waves` 의 `plan_id`·`plan_duration_ms`·`total_cost_krw`·`unassigned_count` 를 채우는 것은
`plan.completed` 다([ADR-024](adr/ADR-024-plan-completed-event.md)). 이 네 칸은 웨이브 단위 값이라
`route.assigned` 로는 채울 수 없다 — 그 이벤트가 없던 동안 이 칸들에는 출처가 없었다.

**프로젝션의 규칙 — 먼저 온 사실로 행을 만들고 늦게 온 사실로 채운다, 부재는 값이 아니다**
([ADR-051](adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md), 2026-09-23, 묶음 B).
이 읽기 모델은 §4.1 의 토픽 열한 개를 받고, **행 하나에 여러 토픽이 쓴다** — `rm_orders` 에 일곱
(`order.placed`·`fulfillment.planned`·`order.dispatched`·`delivery.status`·`order.cancelled`·`delivery.at-risk`·`route.assigned`
— 마지막 것은 2026-09-24 정정에서 더해졌다, 아래), `rm_waves` 와 `rm_routes` 에 각각 넷. 그 사이의 순서는 보장되지 않는다(§4.5) — 같은 outbox
트랜잭션에서 나가도 토픽과 파티션이 다르므로 `plan.completed` 가 일부 `route.assigned` 보다,
`delivery.route-departed` 가 그 라우트의 `route.assigned` 보다 먼저 올 수 있다.

그래서 핸들러는 전부 **upsert** 이고 「행을 만드는 핸들러」를 따로 두지 않으며, **자기 칸만**
쓴다 — 모르는 칸에 `NULL`·`0`·`false` 를 넣지 않는다. 상태 칸들(아래 정정 뒤 넷)은
축 규칙([ADR-017](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md))의
**다섯 번째 자리**다. 개수 칸은 증감이 아니라 **집계**다
([ADR-025](adr/ADR-025-wave-admission-share-lock.md) 와 같은 형태) — `delivery.status` 가
`order.dispatched` 보다 먼저 오면 올릴 라우트가 없기 때문이다. 그리고 **아직 다 오지 않았다고
실패로 표시하지 않는다** — DLQ 도 `rejected` 도 아니다(§4.6).

`rm_waves.route_count` 는 `plan.completed` 의 `routeCount` 가 채우는 **기대치**이고
(「몇 개의 `route.assigned` 를 기다려야 하는가」를 아는 유일한 값, ADR-024), 실제로 도착한
수는 `rm_routes` 를 세서 낸다. 둘은 다른 값이므로 다른 칸이고, 같아지는 순간이 「계획이 전부
도착했다」다. 그 기대치 칸이 위 DDL 에 없었다 — 2026-09-23 에 더했다.

**DDL 정정 — 한 칸에 두 출처의 사실을 접지 않는다** (2026-09-24, ADR-051 재검토 지점 1·2 에
답한다. 새 결정이 아니라 **ADR-051 결정 2 의 적용**이다). 처음 DDL 의 두 칸이 각각 두 출처의
사실을 한 칸에 접고 있었다.

- **`status` 한 칸 → `order_status` · `delivery_outcome` 두 칸.** `rm_orders.status` 하나에
  order 쪽의 상태와 tracking 의 결과를 같이 넣으려니 `CANCELLED` 와 `DELIVERED` 가 충돌했다 —
  취소된 주문이 실제로 배송되는 경우가 있고(`dawnline_cancel_too_late_total` ·
  `dawnline_scan_after_cancel_total` 의 한 쌍), **둘 다 영구히 참이면 둘 다 칸이 있어야 한다.**
  `order_status` 는 주문 쪽 축(`order.placed`·`fulfillment.planned`·`order.dispatched`·`order.cancelled`)만,
  `delivery_outcome` 은 `delivery.status` 만 쓴다. 「취소됐는데 배송됨」은 두 칸의 **조합**
  (`order_status = 'CANCELLED' AND delivery_outcome = 'COMPLETED'`)이고, 그것이 ops 화면이 보여야
  할 예외 목록이다 — dispatch·tracking 의 두 카운터가 세는 것을 ops 는 **행으로** 보여 준다(카운터는
  집계이고 운영자는 개별 답이 필요하다, §6.3).
- **`eta_at` 한 칸 → `planned_arrival` · `eta_at` 두 칸.** ETA 를 싣는 토픽은 `delivery.at-risk`
  뿐이라 위험하지 않은 주문의 ETA 는 ops 에 오지 않는다. 그 부재를 이벤트를 늘려 채우지 않는다
  — 전 stop 의 ETA 를 스캔마다 팬아웃하는 것은 처리량을 부재 하나와 바꾸는 일이다. 대신
  `planned_arrival` 은 `route.assigned` 가 **언제나** 채우고 `eta_at` 은 at-risk 가 **개정됐을 때만**
  채운다. 화면은 `eta_at ?? planned_arrival` 에 「개정됨」 표시를 붙인다 — 계획과 추정을 한 칸에
  섞지 않는다. **그래서 `rm_orders` 에 쓰는 토픽은 일곱이 된다**(`route.assigned` 가 더해졌다).

두 칸의 값과 축은 이렇다(ADR-051 결정 3 — 판정은 `(현재 값, 들어온 값)` 의 순수 함수이고 뒤로
가면 `dawnline_event_stale_total` 로 센다).

| 칸 | 쓰는 토픽 | 축 |
|---|---|---|
| `order_status` | `order.placed` · `fulfillment.planned` · `order.dispatched` · `order.cancelled` | `PLACED(0) → PLANNED(1) → UNSERVICEABLE(2) → DISPATCHED(3) → CANCELLED(4)` |
| `delivery_outcome` | `delivery.status` 만 (`ARRIVED` 는 결과가 아니라 쓰지 않는다) | `FAILED(0) → COMPLETED(1)` |
| `rm_waves.status` | `fulfillment.planned` · `wave.closed` · `plan.failed` · `plan.completed` | `OPEN(0) → CLOSED(1) → PLAN_FAILED(2) → PLANNED(3)` |
| `rm_routes.status` | `route.assigned` · `delivery.route-departed` · `delivery.status` | `ASSIGNED(0) → DEPARTED(1)` |

- **축은 전순서다.** `PLANNED` 와 `UNSERVICEABLE`, `FAILED` 와 `COMPLETED` 는 한 주문에 함께 오지
  않는다(출처가 종료 상태로 보장한다) — 그래도 같은 단계에 두지 않는 이유는 판정이 **최댓값**이
  되어 「어느 순서로 와도 같은 답」이 코드의 성질이 되기 때문이다. `CANCELLED` 가 맨 위인 것은
  order-service 의 사실 그대로다: `order.cancelled` 는 order-service 가 취소를 **받아들였을 때만**
  나가고, 그러면 그 뒤의 `order.dispatched` 는 거기서 거부된다.
- **`order_status` 의 값에 `DELIVERED`·`FAILED` 가 없다.** 그 둘은 `delivery_outcome` 의 것이다.
  배차 불가는 order-service 에서는 `FAILED` 지만 여기서는 `UNSERVICEABLE` 이라 적는다 —
  `fulfillment.planned` 의 `outcome` 이름 그대로이고, 두 칸에 같은 글자 `FAILED` 가 다른 뜻으로
  앉지 않게 한다. `rm_waves.status` 에 `CLOSING` 이 없는 것도 같은 이유다 — fulfillment 내부의
  상태이고 그것을 싣는 이벤트가 없다.
- **아무 칸도 「아직 없음」을 값으로 적지 않는다.** 결과가 아직 없는 주문의 `delivery_outcome`
  은 `'NONE'` 이 아니라 `NULL` 이다 — `order.placed` 가 행을 만들면서 `NONE` 을 적으면 「배송되지
  않았다」는, 아직 아무도 하지 않은 주장을 적는 것이다(결정 2).
- **`rm_routes.status` 는 `delivery.status` 도 쓴다** — 배송이 일어났다면 라우트는 출발한 것이다
  (ADR-017 의 「건너뜀은 사실」). 기사가 `DEPARTED_CAMP` 스캔을 빼먹어도(§5.4 가 허용한다) 화면이
  「출발 안 함」에 머물지 않는다. 그때 `departed_at` 은 `NULL` 로 남는다 — 출발했다는 것은 알지만
  **언제**인지는 모른다.

**정정이 드러낸 칸 다섯** — 두 칸이 순서와 무관하려면 필요한 것들이다. 설계서의 DDL 에 없었다.

- **`rm_routes.revision`.** §6.8 4단계가 이미 「tracking·ops 는 revision 이 낮은 이벤트를 무시」라고
  적어 두었는데 그 비교를 할 칸이 없었다. `route.assigned` 가 쓰는 라우트 칸(`plan_id`·`vehicle_id`·
  `driver_id`·`stop_count`·`distance_m`·`cost_krw`·`planned_departure`)은 이 번호로 거른다
  (tracking 의 `route_revisions` 와 같은 술어 `<`, [ADR-045](adr/ADR-045-revision-comparison-is-per-route.md)).
- **`rm_orders.planned_as_of`.** 주문의 계획 칸(`route_id`·`planned_arrival`)은 라우트를 **넘어**
  비교해야 한다 — 재계획이 주문을 A 에서 B 로 옮기면 A 의 옛 개정과 B 의 새 개정이 서로 다른
  파티션에서 온다. `revision` 은 라우트마다 독립이라 라우트를 넘어 견줄 수 없으므로(ADR-045 가
  버린 대안 둘째와 같은 이유), 견주는 값은 **발행자의 사건 시각**(봉투의 `occurredAt`)이다.
  계획을 내는 것은 dispatch 하나이고 재계획은 최초 계획보다 뒤의 트랜잭션이다. **그래서 `route_id`
  의 출처가 `order.dispatched` 에서 `route.assigned` 로 옮겨 온다** — `order.dispatched` 는 최초 계획
  에만 나가고(§6.8 은 그것을 다시 내지 않는다) 옮긴 주문은 개정에만 나타나므로, 그 칸을 두 토픽이
  나눠 쓰면 재계획 뒤에 두 값이 갈라진다. `order.dispatched` 는 이제 `order_status` 만 쓴다.
- **`rm_orders.eta_as_of`.** at-risk 는 반복되고(ADR-046) 주문이 옮겨 가면 두 라우트의 at-risk 가
  서로 다른 파티션에서 온다. 견주는 값은 페이로드의 `detectedAt` 이다.
- **`rm_routes.planned_departure` · `departed_at`.** [ADR-050](adr/ADR-050-route-departure-is-an-event.md)
  이 지키려는 「출발 안 함」과 「출발했는데 아직 도착 없음」의 구별은 `departed_at` 이 있어야 하고,
  「출발했어야 하는데 안 했다」는 **출발 전에** 계획 출발 시각을 알아야 한다 — 그래서 뒤의 것은
  `route.assigned` 의 `summary.plannedDeparture` 에서 온다(개정으로 거른다).

**판정 키 — 계획 칸은 revision 이, 추적 칸은 사건 시각이 판정한다** (2026-09-24).
[ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 의 「계획은 `(route, revision, seq)` 로,
사실은 `orderId` 로」를 읽기 모델로 옮긴 한 줄이다. 한 행에 두 계열이 같이 앉으므로(주문 행에 계획
도착과 배송 결과, 라우트 행에 계획 거리와 출발 시각) 칸마다 계열을 적고(`ColumnFamily`), 판정 키는
계열이 정한다.

| 계열 | 칸 | 판정 키 |
|---|---|---|
| 계획 | `rm_routes` 의 `plan_id`·`vehicle_id`·`driver_id`·`stop_count`·`distance_m`·`cost_krw`·`planned_departure` | `revision` |
| 계획 | `rm_orders` 의 `route_id`·`planned_arrival` | `planned_as_of` = 계획 이벤트(`route.assigned`)의 `occurredAt` — **라우트를 넘기 때문이다**. 재계획이 주문을 R1 에서 R2 로 옮기면 R1 개정 1 과 R2 개정 2 를 견줘야 하는데 `revision` 은 라우트마다 독립이라 견줄 수 없다([ADR-045](adr/ADR-045-revision-comparison-is-per-route.md)). 계획을 내는 것은 dispatch 하나이므로 그 발행 시각이 계획의 순서다 |
| 추적 | `eta_at` | `eta_as_of` = at-risk 의 `detectedAt` |
| 추적 | `delivery_outcome`·`delivered_at` | **추적 축**(`FAILED → COMPLETED`) — 시각이 아니다. 종료 상태는 한 번 오고, 뒤 시각의 `ARRIVED` 가 `COMPLETED` 를 덮으면 안 된다 |
| 추적 | `departed_at`·`at_risk` | 라우트에 하나뿐인 사실이라 견줄 둘째 값이 없다 |

**판정 키는 언제나 사실을 낸 쪽의 시계다 — 소비자의 `now()` 가 아니다.** 「늦게 온 사실이 이긴다」를
도착 순서가 아니라 사건 순서로 판정하는 칸이라, 소비자의 시계를 적으면 그 칸이 다시 도착 순서가
된다. 웨이브의 계획 칸(`plan.completed` 가 쓰는 다섯)은 판정 키가 없다 — 웨이브에 한 번만 온다(ADR-024).

지키는 것은 둘이다. `ColumnFamilyTest` 가 순서 검사와 같은 시나리오를 인과 순서와 셔플 20회로 돌며
(1) 토픽이 자기 계열의 칸과 키·축만 쓰는지, (2) 판정 키가 있는 칸이 판정 키와 **같은 패치에서만**
쓰이는지 본다. `ProjectionShuffleTest` 는 시계를 EPOCH 에 멈춘 채 판정 키가 사건 시각과 같은지 본다 —
핸들러가 시계를 읽었다면 거기서 드러난다.

**생성 칸 둘.** `on_time_promised`·`on_time_revised` 는 핸들러가 쓰지 않고 PostgreSQL 의 생성 칸
(`GENERATED ALWAYS AS … STORED`)으로 둔다. 입력(`delivery_outcome`·`delivered_at`·`promised_end_*`)이
세 토픽에서 오므로, 핸들러가 계산하면 **셋 중 마지막으로 온 핸들러**만 맞는 값을 적는다 — 그
「마지막」은 그날의 순서다. 쓰는 사람이 없는 칸은 순서를 탈 수 없다. `FAILED` 는 약속과 무관하게
`false`, 결과나 약속을 아직 모르면 `NULL` 이다.

**키를 이루는 불변 속성은 싣는 토픽 모두가 쓴다 — 먼저 온 것이 쓰고 덮지 않는다.**
웨이브의 `(camp_id, service_tier, cutoff_at)` 은 웨이브 키 그 자체이고(§5.2) 라우트의 `camp_id` 도
그렇다. 여러 토픽이 그 사본을 싣고(`fulfillment.planned`·`wave.closed`·`plan.*`, `route.assigned`·
`delivery.at-risk`·`delivery.route-departed`) 값이 **같으므로**, 이것은 두 출처의 사실이 아니라 한
사실의 사본이다. 먼저 온 행이 캠프 대시보드에 보이려면 그 칸이 필요하다. 이 예외는 **키에만**
적용한다 — 개정으로 바뀔 수 있는 값(`stop_count`·`planned_departure`)은 사본이 아니라 그 개정의
사실이므로 한 토픽만 쓴다.

**인덱스 둘 — 개수를 다시 세는 질의의 것** (2026-09-24, ADR-051 결정 4 가 미뤄 둔 판단,
[측정](benchmarks/phase6-rm-orders-aggregate-index.md)). `rm_orders` 에는 보존 정책이 없어 피크일
15만 행씩 쌓이고, 재집계는 **이벤트마다** 돈다(`delivery.status` 피크 673 건/초). 인덱스 없이
라우트 재집계가 1일치 9.2 ms · 30일치 172 ms 이고, `(route_id)`·`(wave_id)` 로 0.19–0.31 ms 에서
평평하다. 다른 질의(`lock` 의 키 조회)는 PK 로 충분하다 — 그 판단도 같은 문서에 있다.
`RmOrdersIndexIT` 가 통계를 첫 어설션으로 말한 뒤 두 계획을 본다.

**KPI — 두 축, 뷰** (2026-09-24, 묶음 B 의 KPI 단계. 새 결정이 아니라 **ADR-051 결정 4 의 가장 순수한
형태**다). V1 의 `rm_kpi_hourly` 는 이벤트가 증감하는 표였다. 개수가 증감이 아니라 집계여야 한다면
(결정 4) 가장 깨끗한 집계는 **쓰는 쪽이 없는 것**이다 — 쓰는 핸들러가 없으면 순서 문제도 없다. 그래서
V2 가 그 표를 지우고 `rm_orders` 위의 뷰 둘을 둔다. 뷰는 `rm_` 로 시작하지 않아 프로젝션의 쓰기 집합
밖이고, 순서 검사·열 계열 검사가 그 표에 걸어 두었던 제외도 함께 사라졌다.

**행의 열은 하나의 시간 축을 공유한다.** 「주문 수는 접수 시각 기준, 완료·정시는 배송 시각 기준」을
한 행에 두면 그 행은 코호트가 다른 두 수를 나란히 놓고 「100건 중 80건 배송」처럼 읽힌다 — 한 기준의
정시율만 있던 것과 같은 부류의 오독이다. 그래서 축마다 뷰가 하나다. 화면이 두 축을 나란히 그릴 수는
있지만 표가 같은 행에서 합치지 않는다.

| 뷰 | 버킷 | 열 |
|---|---|---|
| `kpi_intake_hourly` | `placed_at` (`order.placed` 의 `placedAt`) | `orders` · `unserviceable` |
| `kpi_delivery_hourly` | `COALESCE(delivered_at, failed_at)` — 결과가 난 시각 | `delivered` · `failed` · `on_time_promised` · `on_time_revised` · `revised` · `outcome_without_promise` |

- **버킷은 UTC 정시다** — `date_trunc('hour', …, 'UTC')`. §5.4 의 파티션 경계와 같은 이유로 세션 존에
  따라 움직이지 않고, 세 인자 형태는 `IMMUTABLE` 이라 인덱스 식이 될 수 있다(두 인자 형태는 `STABLE`
  이라 안 된다 — PG 18.2 의 `pg_proc` 에서 확인).
- **배송 축의 한 행은 한 모집단을 센다** — 결과·캠프·두 약속을 모두 아는, 취소되지 않은 주문.
  약속을 아직 모르는 결과는 분모에도 분자에도 없다 — 「모름」을 「늦음」으로 세지 않는다(결정 2).
  캠프는 개정 약속과 같은 사실(`fulfillment.planned`)로 오므로 캠프를 모르는 것은 약속을 모르는 것의 한 형태다.
  취소된 주문은 배송됐어도 빠진다 — 약속이 더는 서 있지 않고, 그 주문은 예외 목록의 행이다(위 「DDL 정정」).
- **그러나 빠진 수는 보인다 — `outcome_without_promise`** (2026-09-24, 리뷰에서 더했다). 분모에서 조용히
  빠지는 것은 실패를 빼서 정시율을 올리는 것과 같은 부류다. **부재는 값이 아니지만 부재의 수는 값이다.**
  같은 행, 같은 시간 축에 모집단 밖의 결과를 센다 — 캠프를 모르는 결과는 `camp_id IS NULL` 행에 있고 그
  행의 정시율 칸은 0 이다(접수 축의 배차 불가와 같은 모양). 정상에서 이 수는 프로젝션 랙만큼의 일시값이고,
  **계속 0 이 아니면 `fulfillment.planned`(또는 `order.placed`)가 오지 않고 있다.** 취소는 세지 않는다 —
  모름이 아니라 정의로 빠진 것이다. 모집단 판정(`known`)은 뷰의 안쪽 질의에 한 번만 적고, 플래너가 그것을
  끌어올려 버킷 술어는 여전히 인덱스의 식 그대로 내려간다(`KpiViewsIndexIT`).
- **`late` 는 없다** — `delivered + failed − on_time_*` 로 유도된다. 세는 것과 다시 세는 것의 구분 그대로다.
- **`revised`** 는 그 버킷에서 완료된 주문 중 약속의 끝이 개정된 수(`promised_end_revised <> promised_end_original`).
  두 정시율의 격차가 개정의 효과를 보여 주지만, 격차만 있고 건수가 없으면 「몇 건을 개정해서 얻은
  격차인가」를 읽지 못한다 — §5.2 의 「원래 약속과 개정 횟수를 함께」가 이 열이다.
- **배차 불가는 캠프가 없는 행에만 있다** — `fulfillment.planned(UNSERVICEABLE)` 는 캠프를 싣지 않는다(§4.3).
  `kpi_intake_hourly` 의 `camp_id IS NULL` 행이 그것이고, 그 행의 `orders` 에는 `fulfillment.planned` 가
  아직 오지 않은 주문도 들어 있다(「아직」). 둘은 `unserviceable` 칸으로 갈린다.
- **`dispatched`·`cost_krw` 는 시간 버킷에 두지 않는다** — `rm_orders` 에 배차 시각이 없고, 비용은
  웨이브의 값이라(`rm_waves.total_cost_krw`) 어느 시각의 버킷에 속하는지가 없다.

**`failed_at` — 사실 하나에 칸 하나.** 분모에 실패가 들어가려면 실패도 배송 축의 시각이 있어야 하는데,
`delivered_at` 은 `COMPLETED` 만의 시각이었다. `failed_at` 은 `delivery.status(FAILED)` 의 `occurredAt` 이고,
`delivered_at` 과 **같은 추적 축**이 판정한다 — 축이 `FAILED` 로 옮길 때만, 결과와 같은 패치에서 쓰인다
(`ColumnFamilyTest` 의 판정 키 표에 둘 다 있다). 「결과 시각」 한 칸(`outcome_at`)을 두지 않은 것은
`COMPLETED` 행에서 `delivered_at` 과 같은 사실을 두 칸에 적게 되기 때문이다 — ADR-048·050 에서 계속 지운
「같은 사실의 둘째 출처」다.

**두 시각은 배타다 — 문장이 아니라 제약으로** (`ck_rmo_outcome_time_exclusive`). 추적 축에서 `COMPLETED`
와 `FAILED` 는 둘 다 종결이라 한 주문이 둘을 다 갖지 않고, 버킷 `COALESCE(delivered_at, failed_at)` 은 그
배타성 위에서만 옳다. 축은 `FAILED → COMPLETED` 를 앞으로 가는 것으로 판정하므로 그 순서를 막는 것은
축이 아니라 이 제약이다 — 그런 사실이 오면 적재가 실패하고 재시도·DLQ 의 길로 간다(`KpiViewsIT` 가
출처가 내지 않는 그 사실을 만들어 넣고 롤백을 본다). **재검토 조건: 재배송.** Phase 5 에서 미룬 재배송이
들어와 실패 뒤 완료가 생기면 여기서 멈춘다. 그때 먼저 정할 것은 **재배송이 새 shipment 인가, 같은 행의
둘째 결과인가**다 — 앞이면 주문 행은 결과를 둘 갖는 것이 아니라 시도를 둘 갖는 것이고(행의 키가 바뀐다),
뒤면 이 제약과 버킷 식을 함께 바꾼다.

**인덱스 둘 — 뷰의 버킷 식 그대로** ([측정](benchmarks/phase6-kpi-hourly-views-index.md), 불변규칙 11).
peak 30일(450만 행)에서 캠프 하나의 24 버킷: 배송 축 212.2 → 6.38 ms, 접수 축 187.4 → 4.98 ms, 게이지(전
캠프 24 버킷) 474 → 39.6 ms. 뷰의 `bucket_hour` 술어가 뷰 안으로 내려가 인덱스의 식과 만나야 하므로
**두 식은 글자 그대로 같아야 한다.** 어긋나면 플래너는 조용히 다른 길로 간다 — 측정한 음성 표본
(`COALESCE(failed_at, delivered_at)`)은 순차 스캔도 아니고 **다른 인덱스의 캠프 접두**만 타며 43만 행을
걸렀다(158 ms). 그래서 `KpiViewsIndexIT` 는 인덱스 이름이 아니라 `Index Cond` 에 버킷 식이 있는지를 본다.

**느려지면 다음 단계는 증분 쓰기가 아니라 materialized view 의 주기 refresh 다** — 여전히 다시 세는
것이고, 쓰는 핸들러가 생기지 않는다. 뷰 이름을 그대로 두고 `CREATE MATERIALIZED VIEW` 로 바꾸면
읽는 쪽(게이지·화면)은 바뀌지 않는다. 그 전에 볼 것은 `rm_orders` 의 보존이다 — 지금 보존 정책이 없어
뷰의 비용이 날마다 는다(위 「인덱스 둘」과 같은 전제).

**정시율 게이지** — `dawnline_delivery_on_time_ratio{camp, basis}`(§9.1)는 **이 뷰를 읽는다**
(`OnTimeRatioGauges`). **창은 「직전 24시간」이 아니라 「현재 버킷 포함 UTC 정시 버킷 24개」다** — 현재
버킷은 늘 부분이라 창의 길이는 23시간 남짓에서 24시간 사이를 움직인다(2026-09-24 승인). 이유는 둘이다:
① 게이지가 뷰를 읽으므로 **대시보드의 24행과 게이지가 다른 수를 말할 수 없다** — 같은 사실을 두 경로로
세면 언젠가 갈리고, 갈린 날 운영자는 어느 쪽을 믿을지 모른다. ② 원시 시각으로 정확히 24시간을 자르는
질의는 플래너가 행 수를 269 로 추정해(실제 11.8만) 10일치에서도 순차 스캔을 골랐다. 1분마다 다시 센다.
분모는 `delivered + failed` — **실패는 분모에 있고 분자에 없다; 실패를 빼면 정시율이 오른다.** 창에 결과가
없는 캠프, 갱신이 실패한 동안의 모든 캠프는 `NaN` 이다 — 0 은 「전부 늦었다」는 주장이고 멈춘 값은
건강해 보인다.

같은 갱신이 두 값을 더 낸다 — 정시율이 **정직한지**를 말하는 값이다.

- `dawnline_kpi_excluded{reason="promise_unknown"}` — 같은 창의 `outcome_without_promise` 합(캠프가 없는 행
  포함). 모르면 `NaN`.
- `dawnline_kpi_refresh_age_seconds` — 마지막으로 **성공한** 갱신 뒤로 흐른 초를 스크레이프마다 계산한다.
  **NaN 은 정직하지만 아무도 못 듣는다**: Prometheus 의 `< 0.95` 는 `NaN` 에 대해 거짓이라 갱신이 죽으면
  정시율 알림이 조용해진다. 그래서 알림은 이 값에 건다(§9.4) — `dawnline_shipment_partitions_ahead` 가
  「만든 수」가 아니라 「남은 날」을 재는 것과 같은 모양이다. 성공한 적이 없으면 기동부터 센다.

**`updated_at` 은 사실이 아니라 프로젝션의 기록이다** — 마지막으로 행을 만진 시각이라 정의상 처리
순서를 탄다. 순서를 뒤섞는 IT 가 비교에서 빼는 칸은 이것 하나이고, 그 IT 는 칸도 토픽처럼
**빼는 방식**으로 돈다(칸 목록을 `information_schema` 에서 읽는다).

**ops-web (React, 최소 범위)** — 화면 4개: ① 캠프 대시보드(웨이브 상태·정시율·미배정·계획 시간) ② 웨이브/계획 상세(라우트 목록, 비용, 설명 조회) ③ 라우트 지도(stop 순서 폴리라인, 진행 상태, at-risk 강조) ④ 룰 편집. 지도는 Leaflet + OpenStreetMap 타일 `[결정 필요: 타일 서버 정책상 데모 용도 확인]`.

### 5.6 sim-runner / benchmark 도구

- `sim-runner` (Spring Boot CLI, `tools/sim-runner`): 시나리오 YAML로 (a) 주문 생성기 — 캠프별 좌표 분포, 티어 비율, 냉장 비율, 초당 rps 곡선(평시·피크) (b) 기사 시뮬레이터 — `route.assigned` 구독 후 stop을 순서대로 이동하며 스캔 이벤트 호출, 지연·실패 확률 주입.

  **시뮬레이션 시각은 벽시계가 아니다** (Phase 5-2). 스캔의 `occurredAt` 은 계약의
  `plannedDeparture`·`plannedArrival` 에 seed 에서 뽑은 지연을 더한 값이고, `Instant.now()` 가
  아니다(불변규칙 12). 그래야 *주입한 지연이 곧 tracking 이 계산하는 편차*가 되어 「늦었다」를
  값으로 확인할 수 있다. 배속(`speed`)은 호출 사이의 대기에만 닿고 페이로드에 닿지 않으므로,
  같은 seed 는 배속과 무관하게 같은 스캔 열을 낸다.
  그 결과로 적어 둘 것 하나: `route:{id}:atrisk:cooldown` 의 TTL 은 **벽시계** 5분이라
  압축된 시간에서는 라우트당 at-risk 가 한 번만 보인다 — **시뮬레이터의 제약이지 tracking 의
  규칙이 아니다**([ADR-046](adr/ADR-046-at-risk-is-an-event.md): 쿨다운이 지키는 것은 알림 수다).
  시나리오의 어설션은 「at-risk 가 났다」까지이고, 「몇 번 났다」는 배속 1에서만 의미가 있다.

  시뮬레이터는 개정을 받으면 **현재 위치에서 다시 계획한다**. 그래서 「라우트 → 스캔 열」이 아니라
  「라우트 + 현재 위치 → 남은 스캔 열」이 순수 함수의 모양이고, 이미 끝낸 stop 은 새 개정이 뭐라
  하든 다시 스캔하지 않는다 — tracking 의 「개정은 종결을 되돌리지 않는다」와 대칭이며 축도 같다
  (`seq` 가 아니라 주문 id). 이것이 있어야 Phase 5-3 의 「at-risk → 재계획 → revision 반영」에서
  *기사가 새 순서를 따르는지*를 tracking DB 밖에서 확인할 수 있다.
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
record VehicleSpec(VehicleId id, Capacity capacity, VehicleAttrs attrs, TimeWindow shift, VehicleCost cost) {}
record Stop(GeoPoint point, List<OrderId> orderIds, Parcel parcel, TimeWindow promised,
            int serviceSeconds, int priority) {}                    // §6.5 1단계 통합 결과
record PlanResult(List<PlannedRoute> routes, List<Unassigned> unassigned, Money totalCost,
                  PlanMetrics metrics, List<Explanation> explanations) {}
record PlannedRoute(VehicleId vehicle, List<PlannedStop> stops, int distanceM, int durationS, Money cost) {}
```

**타입 선택 셋** (2026-09-05, Phase 3-1 구현 시 확정)

- **금액은 `long` 이 아니라 `libs/common` 의 `Money`.** 불변규칙 9 가 요구하는 것은 정수 KRW 이고
  `Money` 가 그 타입이다. `long` 으로 두면 거리(m)·시간(s)과 같은 `long` 이라 인자 순서가 바뀌어도
  컴파일된다.
- **근무창은 `ShiftWindow` 가 아니라 `TimeWindow`.** 반열린 구간의 의미와 연산이 이미 있고,
  같은 뜻의 타입을 하나 더 두면 둘 중 하나에만 경계 규칙이 붙는다.
- **`OrderId`·`VehicleId` 는 감싼다** — 저장소의 다른 곳은 raw `UUID` 를 쓰는데 여기만 다르다.
  이유는 이 패키지가 **여러 종류의 id 가 한 함수 안에서 섞이는 유일한 곳**이기 때문이다.
  `Map<OrderId, …>` 와 `Map<VehicleId, …>` 가 나란히 있고 `assign(orderId, vehicleId)` 같은 서명이
  있는 자리에서 raw `UUID` 는 인자를 바꿔 넣어도 조용히 컴파일된다. 애그리거트 하나의 id 만
  다루는 다른 서비스에서는 그 위험이 없으므로 그쪽은 그대로 둔다.

`DistanceProvider`는 `(GeoPoint a, GeoPoint b) → (meters, seconds)`를 반환. 기본 구현 `HaversineDistance`(도로계수 1.3, 평균 속도 25 km/h, 캠프 설정값). 선택 구현 `OsrmDistance`(테이블 API, 캐시). 문제 생성 시 거리 행렬은 **stop 통합 후** 계산해 `O(n²)` 규모를 줄인다(§6.7).

### 6.3 룰 엔진

**설계 원칙**: 룰은 데이터(DB)로 정의하고 타입별 평가기는 코드로 제공한다. 룰은 **하드**(위반 시 배정 불가)와 **소프트**(비용 가산)로 나뉜다. 모든 평가 결과는 `Explanation`으로 남겨 운영자가 "왜 이 주문이 미배정인지 / 왜 이 차량인지"를 볼 수 있다.

```java
sealed interface DispatchRule permits HardRule, SoftRule, UnassignedRule {
  String name(); int priority();
}
interface HardRule       extends DispatchRule { Feasibility check(Stop s, VehicleSpec v, RouteState r); }
interface SoftRule       extends DispatchRule { Money penalty(Stop s, VehicleSpec v, RouteState r); }
interface UnassignedRule extends DispatchRule { Money penalty(Stop s); }   // 배정 실패 시의 비용
```

**세 번째 종류가 필요한 이유** (2026-09-05, Phase 3-2 구현 시): `UNASSIGNED_PENALTY` 는 심각도가
SOFT 지만 **평가 시점이 다르다** — 배정에 실패한 주문에 붙는 비용이라 차량도 라우트 상태도 없다.
앞의 두 서명에 억지로 끼우려면 둘 중 하나를 널 허용으로 열어야 하고, 그러면 <em>모든</em> 소프트
룰이 "차량이 없을 수도 있다" 를 방어해야 한다. 심각도가 아니라 평가 시점으로 갈리는 종류라
`sealed` 의 세 번째 자리에 둔다 — 새 시점이 또 생기면 처리하지 않은 분기가 컴파일 에러로 드러난다.

`RouteState` 는 차량뿐 아니라 **캠프와 거리 제공자**를 함께 들고 있다. `SHIFT_WINDOW` 가
"복귀 시각 ≤ 근무 종료 − 버퍼" 를 판정하려면 **캠프로 돌아가는 구간**이 필요하고, 그 구간은 라우트의
일부이지 룰의 파라미터가 아니기 때문이다. 같은 이유로 `TIME_WINDOW_LIMIT` 은 "이 stop 을 붙였을 때의
도착 시각" 을 `RouteState` 에 물어본다.

평가 단위가 `Candidate` 가 아니라 **`Stop`** 인 것은 §6.5 의 1단계가 통합이기 때문이다 — 통합
이후로는 주문 하나가 단독으로 배정되는 일이 없고, 용량·냉장·우선도는 전부 **합쳐진 값**으로 봐야
한다(`Stop.parcel` 은 중량·부피의 합이고 냉장·위험물은 OR, `priority` 는 최댓값). 배정에 실패하면
그 stop 의 주문이 **함께** 미배정이 되고, `Unassigned` 목록에서 다시 주문 단위로 펼친다.

**룰은 «판정» 말고 두 가지를 더 답한다** (2026-09-12, [ADR-037](adr/ADR-037-reinsertion-prunes-what-cannot-fit.md)·[ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)).
룰이 데이터라는 것은 **최적화가 룰의 파라미터를 읽지 않는다**는 뜻이다. 그런데 최적화는 룰의
*결과*만으로는 답할 수 없는 질문이 있다 — 「이 라우트는 어느 자리를 봐도 안 되는가」(재삽입
가지치기)와 「라우트 하나에 몇 개까지인가」(클러스터 크기). 그래서 파라미터를 여는 대신
**룰이 답하는 질문을 늘린다.**

| 질문 | `HardRule` 의 메서드 | 기본값 | 답하는 룰 |
|---|---|---|---|
| 판정이 순서가 아니라 집합에만 달렸는가 | `positionIndependent()` | `false` | `MAX_STOPS_PER_ROUTE` · `VEHICLE_CAPACITY` · `VEHICLE_ATTRIBUTE_MATCH` |
| 라우트 하나의 stop 상한은 얼마인가 | `routeStopCap()` | `OptionalInt.empty()` | `MAX_STOPS_PER_ROUTE` |

둘 다 **기본값이 「모른다」** 인 것이 핵심이다. 새 룰이 조용히 대상이 되면 답이 달라지므로,
표시는 그 룰을 아는 사람이 직접 단다. 여럿이 답하면 `RuleSet` 이 합친다 — stop 상한은 **최솟값**
(상한은 동시에 성립해야 한다), 위치 무관성은 룰마다 따로다.

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
| PRIORITY_BOOST | SOFT | bonusKrw, halfLifeMinutes | 우선 고객(priority>0)에게 **먼저** 가면 보너스(음의 페널티). 계획 시작 대비 도착 시각으로 감쇠 |
| VEHICLE_PREFERENCE | SOFT | preferredTypes, penaltyKrw | 소형 물량에 대형 차량 배정 시 페널티 |
| UNASSIGNED_PENALTY | SOFT | baseKrw, perPriorityKrw | 미배정 비용 (티어별 차등) |

**`priority` 는 어디서 오는가 — 선언이 아니라 파생이다** (2026-09-09, [ADR-028](adr/ADR-028-unassigned-policy.md)).
위 표의 두 룰(`PRIORITY_BOOST`·`UNASSIGNED_PENALTY`)이 `priority` 를 읽는데, 그 값의 출처가
설계서에 없었다. 계약에 필드를 넣는 길은 두 가지로 막힌다 — **클라이언트 값은 신뢰할 수 없고**
(§10, 고객 API 는 무인증이라 `customerId` 조차 클라이언트 주장값이다), **티어에서 파생하면
상수가 된다**(웨이브가 (캠프, 티어, 컷오프) 단위라 한 계획 안의 모든 후보가 같은 티어다).
아무것도 가르지 못하는 값은 우선순위가 아니다.

한 계획 <em>안에서</em> 실제로 달라야 하는 것은 **"이 주문을 우리가 이미 얼마나 실망시켰는가"**
이고, 그것은 dispatch 가 후보 적재 시점에 **이미 받은 사실**이다. 점수표는 룰과 같은 이유로
데이터다(설정 `dawnline.dispatch.priority.*`).

| 사실 | 가중치 | 근거 |
|---|---:|---|
| `promiseRevised` | **+2** | [ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 의 개정은 <em>한 번 깬 약속</em>이다 |
| `requiresCold` | **+1** | 미배정의 비용이 다른 주문보다 크다 (cold-chain) |
| 배송 실패 후 재배송 | +3 | **Phase 5** — 그 사실은 tracking 이 만든다. 사실이 오기 전에 가중치만 먼저 두지 않는다 |

**범위 밖 — VIP 같은 고객 등급 우선순위.** 고객 서비스가 없어 등급의 출처가 없고, 있는 척하면
`serviceTier` 를 등급으로 몰래 읽는 코드가 된다.

**`PRIORITY_BOOST` 의 감쇠식 — 「앞 순서에」를 무엇으로 재는가** (2026-09-12,
[ADR-040](adr/ADR-040-priority-boost-decays-in-time.md)). 이 표의 정책 문장은 「우선 고객을 앞
순서에 두면 보너스」인데, **상수 보너스는 총비용에서 순서를 구별하지 못한다**(어디에 놓든 같은
금액이라 개선 단계가 우선 고객을 뒤로 밀어도 비용이 그대로다). 그래서 식에 무엇인가가 들어가야
하고, 설계서가 그것을 적지 않은 동안 구현이 **순번**을 골라 두고 있었다(`÷ position`). 이번에
설계서가 적는다 — <strong>시각</strong>이다.

```
bonus(stop) = bonusKrw × priority × halfLifeMinutes ÷ (halfLifeMinutes + t)
  t = 계획 시작부터 그 stop 의 계획 도착 시각까지의 분
```

기준점은 **계획 시작**이지 라우트 출발이 아니다. 계획 안의 모든 라우트가 같은 0점을 봐야
「라우트를 쪼갤수록 앞자리가 늘어난다」가 생기지 않는다 — 라우트 기준으로 재면 순번 자의 결함을
시각으로 다시 만드는 것이다. 그림자 계측이 순번 자를 반증했다: `large` 에서 거리·시간·지각이
<em>전부</em> 좋아진 계획을 그 항 하나가 **+64,018원**으로 벌했는데 두 계획의 총비용 차이는
−58,084원이었다 — **재는 자가 재려는 차이보다 크게 흔들렸다**([측정](benchmarks/phase4-priority-boost.md)).

> **의존 경고 —** 이 자는 **§2.2 의 「조기 배송 허용 · 지각만 벌한다」 위에서만 옳다.** 그 모델에서
> 「이르다」가 그 자체로 좋은 것이기 때문이다. 고객이 약속창을 고르는 티어가 생기거나 조기 배송이
> 비용이 되는 변경은 **이 감쇠의 기준점을 함께 재검토해야 한다** — 그때의 후보는 약속창 시작
> 기준이고, 지금 그것을 고르지 않은 이유는 «이 데이터에서 우선 stop 의 76%가 약속창이 열리기 전에
> 도착해 상수가 되기» 때문이지 그 정의가 틀려서가 아니다. [ADR-023](adr/ADR-023-fulfillment-retention.md)
> 의 보존 기간이 ADR-020 의 24시간에 매여 있는 것과 같은 종류의 결합이며, 마찬가지로 **자동으로
> 강제되지 않는다.**

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
{"orderId":"…","outcome":"ASSIGNED","routeId":"…","ruleName":"reserved-seat","detail":{"marginalCostKrw":1840,"reason":"희소한 제약 조합의 수요를 위해 예약된 좌석입니다"}}
```

**`ruleName` 은 룰 이름만 담지 않는다.** 배정·미배정을 만든 판정이 룰이 아닐 수 있다 —
`plan-deadline`(마감 때문에 시도하지 못했다, [ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md)),
`no-feasible-vehicle`(어떤 룰도 거절하지 않았지만 실을 차가 없다),
`reserved-seat`(희소 조합에 예약된 좌석이라 이 주문이 다른 차로 갔다,
[ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md)). 룰이 아닌 사유는 kebab-case 로 적고,
룰 이름과 겹치지 않게 둔다 — `dispatch_rules.name` 은 (camp_id, name) 유니크라 이 이름들을
룰로 만들 수는 있지만, **만들지 않는다**(§6.3 은 룰을 데이터로 뒀고 이 셋은 계산되는 값이다).

### 6.4 비용 모델

`CostModel`은 차량 비용(고정·km·분)과 룰 페널티를 합산하는 순수 함수. 파라미터는 `dispatch_rules`와 `vehicles`에서 오며 코드에 상수를 두지 않는다. 단위는 KRW 정수(부동소수 금지).

### 6.5 알고리즘 파이프라인

```
plan(problem, budget):
  1. 전처리
     - 같은 geohash7 + 같은 약속창 + **같은 제약(냉장·위험물)** 인 후보를 하나의 Stop으로 통합 (중량·부피 합산)
     - 하드 룰 사전 필터: (stop, vehicleType) 실행 가능 행렬 계산
     - Stop 간 거리 행렬 계산 (DistanceProvider, 통합 후)
  2. 클러스터링 (Sweep)
     - 캠프 기준 극각(angle)으로 정렬 → 용량·max-stops 한도 내에서 연속 구간을 클러스터로 자름
     - 목표 클러스터 수 = min(차량 수, max(중량, 부피, **ceil(stop 수 / routeStopCap)**)) (ADR-041)
     - 권역(zone) 경계를 넘을 때는 ZONE_AFFINITY 페널티를 고려해 자르기 우선
  3. 차량 할당 (Greedy, 최소 한계비용)
     - 클러스터를 가장 이른 promised_end 순으로 정렬
     - 각 클러스터에 대해 실행 가능한 차량 중 marginalCost 최소 차량 선택
       ※ **빈 차가 있으면 빈 차들 중에서** 고른다. 없을 때만 이미 실은 차를 본다 (ADR-041)
       ※ 그 전에 **제약 조합별로 좌석을 예약**한다 — 희소 조합 차량이 일반 수요로 먼저 차지
          않도록 (ADR-039). 예약은 배정 단계의 것이고 재삽입 전에 풀린다
     - 실행 가능 차량이 없으면 클러스터를 분할(절반)해 재시도, 그래도 없으면 미배정 + 설명
     - **무엇을 미배정으로 남길지는 페널티 최소로 고른다** — 용량이 모자라면 `UNASSIGNED_PENALTY`
       가 싼 것(우선순위가 낮은 것)부터 뺀다
  4. 시퀀싱 (Nearest Neighbor with Time Windows)
     - 캠프에서 출발, 다음 stop = (이동비 + 지각페널티)가 최소인 stop
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

**4단계에서 대기시간을 빼는 이유** (2026-09-05, Phase 3-4 측정): 원래 식은 "거리 + 대기시간 +
지각페널티" 였는데 **이 모델에는 대기가 없다.** `RouteState` 는 도착 즉시 서비스를 시작하고
§2.2·§6.3 은 *지각만* 벌한다 — 조기 배송은 허용된다. 대기를 값으로 매기면 일어나지 않는 일에 돈을
물리는 것이고, 그 결과 뒤 약속창의 stop 이 크게 불리해져 라우트가 **약속창 순서로 같은 부챗살을
세 번 훑는다.** 측정: small 거리 604,745 m → 407,353 m, 총비용 2,374,377 → 1,557,071,
미배정 21 → 6 (`docs/benchmarks/phase3-baseline.md`).

대기를 진짜 비용으로 만들려면 먼저 라우트 모델이 그것을 표현해야 한다(도착을 창 시작으로 미루고 그
시간을 근무창 판정에 넣는 것). 그건 **조기 배송을 금지하겠다는 정책 결정**이라 §2.2 를 먼저 고쳐야
하고, 최적화 안에서 조용히 할 일이 아니다.

**3단계에서 미배정을 고르는 규칙 — 「비싼 것부터 자리를 준다」** (2026-09-05 결정 · 2026-09-09 구현,
[ADR-028](adr/ADR-028-unassigned-policy.md)). 원래 문장은 "그래도 없으면 미배정" 이었고 **어느
주문을 남길지는 말하지 않았다.** 말하지 않으면 아무도 안 정한 것이 아니라 *우연이 정한다* —
마지막 클러스터에 남은 주문이 그대로 떨어졌다. 측정이 그것을 드러냈다: `small` 에서 두 전략의
미배정 건수가 **9 로 같은데 페널티는 20,000원 달랐다.** 건수가 같고 값이 다르면 남긴 대상이
다르다는 뜻이고, 그 차이를 만든 것은 알고리즘이 아니라 부재하는 규칙이다.

규칙은 목적함수(§6.1)를 그대로 따른다 — 싣지 않으면 `UNASSIGNED_PENALTY` 를 물고 실으면 라우트
비용이 오르니, **페널티가 비싼 것부터 자리를 주고 오르는 비용이 페널티보다 쌀 때만 싣는다.**
한 문장이 두 질문에 답한다: "누가 빠지는가" 는 *끝까지 자리를 못 찾은 쪽*이고 그건 싼 것들이다.
두 질문을 두 곳에 적으면 같은 정책이 두 벌이 된다.

자리를 찾는 일은 클러스터가 아니라 **stop 단위**다(`UnassignedRepair`). 3단계의 배정은 "이 묶음이
이 차에 들어가는가" 만 묻기 때문에, 제약이 붙은 stop 하나 때문에 나머지가 통째로 밀려난다. 그래서
탐욕 배정 직후에 한 번, **국소 탐색 뒤에 한 번 더** 부른다 — 개선 단계는 라우트를 짧게 만들어
근무창·약속창에 *자리를 만들기* 때문이다. 재삽입 자체는 개선이 아니라 값싼 탐욕이라 §6.7 의
FAST 모드에서도 돈다. FAST 가 생략하는 것은 5단계(국소 탐색)다.

**3단계는 빈 차를 먼저 본다 — 설계에 없던 문장을 측정이 올렸다** (2026-09-12,
[ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md) · [측정](benchmarks/phase4-cluster-axis.md) §8).
위 문장은 「실행 가능한 차량 중 marginalCost 최소」였는데, 구현은 **빈 차가 있으면 빈 차들 중에서만
고르고 없을 때만 실은 차를 본다.** 근거는 클러스터가 *차 한 대 몫*으로 잘려 있다는 데 있다 —
이미 실은 차들과 섞어 비교하면 고정비를 아끼려고 한 대에 계속 얹게 되고, 그 라우트가 부챗살
여럿을 오가는 지그재그가 된다. 탐욕은 **미래의 미배정 비용을 못 본다.**

이 휴리스틱만 뺀 변형을 재서 값을 확인했다: `peak` 이 21,436,641 → **23,640,486**(+2,203,845)이고
미배정이 2 → **70**(사유의 55건이 `max-stops`)이다. **측정된 휴리스틱이 설계에 없었던 것**이므로
문서를 코드에 맞춘다 — 반대 방향(코드를 문서에 맞추기)은 위 수치만큼 나쁘다.

> 다만 이 수치는 **좌석 예약([ADR-039]) 위에서 측정됐다.** 예약이 없던 동안 빈 차 우선은 희소
> 능력 차량을 일반 수요로 먼저 채우던 그 결함의 일부였다 — 「빈 차」에 조합 차량이 섞여 있었기
> 때문이다. 두 결정은 함께 읽어야 한다.

**3단계의 동률 규칙 — 능력이 적은 차 먼저** (2026-09-08, [ADR-031](adr/ADR-031-least-capable-first-tie-break.md)).
한계비용이 같은 실행 가능 차량이 여럿일 때 무엇을 고르는지가 **적혀 있지 않았고**, 그래서
어댑터의 `ORDER BY code` 가 정하고 있었다. 시드의 캠프별 첫 차량이 냉장이라 작은 웨이브에서는
언제나 냉장차가 뽑혔고, cold-chain DoD 의 공허성 검사가 시각과 시드 배분에 따라 통과·실패했다.

정렬 키는 **넣지 못한 stop 수 → 한계비용 → 능력 순위(비냉장 < 냉장) → 용량(소형 < 대형) →
차량 id** 다. 빈 패킹의 고전 규칙이고 — *특수 자원은 그것을 요구하는 수요에 남겨 둔다* —
마지막 키가 id 인 것은 재현성 때문이다(불변규칙 12).

**위험물 허용은 능력 순위에 넣지 않는다.** 같은 논리로 넣었다가 측정이 반대였다 — `large` 에서
위험물 미배정이 99 → 115 로 *늘고* 총비용이 3.9% 올랐다. 아껴 두기는 아낀 자원이 그 수요와
짝지어질 때만 이득인데, 2% 비율에서는 탐욕이 그 짝짓기를 만들지 못한다(냉장은 25% 라 일어난다).
[측정](benchmarks/phase3-baseline.md) §4-7.

**3단계의 「최소 한계비용」은 좌석 예약 없이는 불완전했다 — 좌석 예약으로 정정했다**
(2026-09-12, [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md);
표시는 [ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) 에서 달았다).
한계비용만 보면 **희소 능력 차량이 일반 수요로 먼저 찬다** — 위험물 차가 지금 최소 한계비용이면
일반 클러스터를 받고, 나중에 오는 위험물 수요는 갈 곳이 없다. 위 동률 규칙은 *동률일 때만* 걸리고
위험물은 그 순위에서 빠져 있으며 `VEHICLE_PREFERENCE` 는 소프트라, 이 문장을 그대로 구현하면
그 자리를 지킬 장치가 하나도 없다.

측정이 그것을 값으로 말했다. 이 문장 **그대로** 구현한 변형(「B」)이 `large` 에서 **−3.83%** 로
현재 구현(−14.52%)보다 크게 나빴고, 차이의 대부분이 **미배정 1,190,000원 — 전부 위험물**이었다
([측정](benchmarks/phase4-cluster-axis.md) §5). 구현의 실패가 아니라 **이 문장의 불완전**이다.
그림자 계측이 처방을 정했다(2026-09-12, [측정](benchmarks/phase4-scarce-seats.md)):
**좌석 예약이고, 예약의 단위는 능력이 아니라 제약 조합**이다
([ADR-033](adr/ADR-033-constraint-classes.md)의 축). 「희소 수요를 요구하는 클러스터를 먼저
배정」은 no-op 이다 — 위험물은 주문의 2%지만 클러스터가 61~120 stop 이라 **모든 클러스터가 그
부류**가 된다(네 데이터셋 100%). 그리고 능력별로 재면 보이지 않는다: `peak` 에서 위험물 차량의
여유 슬롯은 287개인데, 갈 곳 없는 9건이 요구하는 **냉장 ∧ 위험물** 차량 9대의 여유는 **5개**다.

**정정의 내용**(ADR-039). 통합 후 stop 의 제약 조합을 계획 시작 시점에 세고, 조합별로
`min(수요, 그 조합을 실을 수 있는 stop 슬롯)` 만큼을 **차량 인덱스 순 라운드로빈**으로 예약한다.
덜 특정한 수요는 더 특정한 예약에 앉지 못하고, 반대 방향은 자기 버킷이 소진됐을 때만 앉는다.
일반 조합에는 예약하지 않는다 — 아무도 막지 않는 항등이기 때문이다. 예약은 **배정 단계의
것**이라 재삽입·개선 단계는 보지 않으며, 그래서 **예약은 미배정의 사유가 될 수 없다**. 대신
푸는 순서를 정한다: 재삽입 동률의 둘째 키가 **「앉을 자리가 적은 수요부터」**이고, 그것이 위
동률 규칙([ADR-031](adr/ADR-031-least-capable-first-tie-break.md))의 수요 쪽 쌍대다. 일반 수요가
예약 때문에 다른 차로 밀리면 배정 설명에 `reserved-seat` 로 남는다(§6.3).

재기준: `small` 1,490,513 → **1,136,026**(−2.67% → −25.82%) · `medium` 3,919,106 →
**3,893,515** · `large` 8,276,130 → **8,281,646**(+5,516) · `peak` 22,341,428 → **21,774,900**.
**네 데이터셋의 미배정이 9·0·1·10 에서 0·0·0·1 이 됐다.**

**그리고 현재 구현의 첫째 정렬 키는 설계에 없다.** 위 문단의 키 목록에서 첫째인 「넣지 못한
stop 수」는 §6.5 3단계의 「marginalCost 최소」에서 나오지 않는다 — 클러스터를 쪼개지 않으려는
의도로 구현에 들어온 **휴리스틱**이다. 결과는 능력 순서의 역전이다: 냉장 25% 라 거의 모든
클러스터에 냉장 stop 이 섞여 있어 **비냉장 차는 언제나 `leftover` 를 남기고**, 그래서
`large` 에서 트럭이 stop 의 **59%** 를 싣는다(중량은 18~42%만 차 있는 채로). 4-17 의 ⓒ 가 이
키를 쟀고 **바꾸지 않아도 된다**는 답을 냈다 — 좌석 예약은 하드 용량이라 키 위에서 성립한다.
키 자체를 바꾸는 것은 여전히 **휴리스틱 변경이므로 재기준 경로**이고(§6.9), 4-8·4-18 의 몫이다.


**5단계의 이동 넷과 그것을 돌릴 수 있게 한 근사 둘** (2026-09-09, [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md)).
이동은 §6.5 그대로다 — 2-opt(라우트 안 구간 뒤집기) · Or-opt(1~3개 묶음을 라우트 안에서 옮기기) ·
inter-route relocate(1~3개 묶음을 다른 라우트로, 양 방향) · swap(두 라우트의 stop 맞바꾸기).
후보의 값은 **그 순서로 라우트를 다시 만들어** 잰다 — 비용 근사식을 쓰면 룰을 코드에 두 번째로
적는 일이 되고(§6.3 은 룰을 데이터로 뒀다), 두 벌은 갈라진다.

그대로 두면 `large` 에서 한 패스도 끝나지 않으므로 후보를 두 겹으로 줄인다: **이웃 표**
(새 간선이 K=20 최근접 안인 이동만 만든다)와 **거리 선별**(이동 거리가 줄지 않는 후보는 룰을
돌리기 전에 버린다). 둘 다 *개선을 덜 찾는* 방향으로만 틀리고 — 하드 룰은 재구성이 본다 —
대가를 쟀다: 선별을 끄면 `small` 총비용이 1.04% 좋아지고 계획 시간이 **16.7배**가 되며 500 주문에서도
예산이 끝난다([측정](benchmarks/phase4-local-search.md)). **예외 하나**: 원래 라우트를 통째로
비우는 이동은 이득이 거리가 아니라 차량 고정비이므로(§6.4) 선별을 건너뛴다.

**예산은 패스 단위로만 끊는다.** 패스는 통째로 적용되거나 통째로 버려지고, 그래서 결과는
결정적인 수열 S₀ ⊇ S₁ ⊇ … 의 한 원소이며 예산은 *어디까지 갔는지만* 정한다 — 느린 기계에서
앞쪽 원소가 나오지 **다른 답이 나오지 않는다**(불변규칙 12). 종료 조건 셋(국소 최적 · 개선 폭
< 0.1% · 예산 소진) 중 세 번째는 세 데이터셋 어디에서도 발화하지 않았다.

**1단계의 통합 키에 제약이 들어가는 이유** (2026-09-09, [ADR-033](adr/ADR-033-constraint-classes.md)). 통합의 질문은 *이 둘을 한 번에 배송할 수 있는가* 이고, 약속창이 다르면 시각이 막듯 **제약이 다르면 차량이 막는다.** 키에 제약이 없던 동안 통합은 제약을 **전파**했다 — 위험물 한 건이 섞인 건물 stop 이 옆의 평범한 아홉 건을 데리고 위험물 차량을 기다렸고, `large` 에서 미배정 stop 18개가 주문 **89건**을 안고 있었다(stop 하나가 평균 4.9건). **희소한 능력 하나가 인질을 잡는다.**

이것은 벤치마크가 아니라 **모델**의 문제다 — 냉장 ∧ 위험물 차량이 한 대 고장 난 날의 캠프가 정확히 이 상태다. 대가는 stop 수 증가인데, 같은 좌표라 **이동 거리는 늘지 않고** 하차·전달 시간도 원래 주문마다 더하므로 그대로다. 늘어나는 것은 「그 주소를 두 번 취급한다」는 사실이고, 그건 `MAX_STOPS_PER_ROUTE` 소비로 이미 값이 매겨져 있다.

**2단계의 "권역 경계에서 자르기 우선" 은 "자를 때가 됐으면 경계에서" 라는 뜻이다** (같은 측정).
경계마다 자르면 클러스터가 차량 수의 6~10배로 부서지고, 남는 클러스터가 이미 실은 차에 얹혀
그 라우트가 부챗살 여럿을 오가는 지그재그가 된다. 클러스터의 목표 크기는 **차 한 대 몫**이고,
경계는 그 크기의 80%를 넘긴 뒤에만 자르는 이유가 된다.

**그 「차 한 대 몫」이 stop 축을 빠뜨리고 있었다 — 고쳤다** (2026-09-12,
[ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md); 표시는
[ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) 에서 달았다 ·
[측정](benchmarks/phase4-cluster-axis.md)). 구현은 `ceil(총수요 / 가장 큰 차량의 용량)` 이었고
「수요」가 **중량과 부피뿐**이었다. 그런데 이 데이터에서 **무는 축은 언제나 stop 슬롯**이다 —
네 데이터셋의 클러스터 **64개가 전부 stop 축에 묶이고**(`bindCap = 0`), 그래서 `large` 의
클러스터가 **상한 120 에 244~341 stop**, `peak` 은 135~373 stop 이었다. 차 두세 대 몫을 한
묶음으로 들고 간 것이다.

고친 식은 축을 하나 더할 뿐이다 — `max(중량, 부피, **ceil(stop 수 / routeStopCap)**)`, 상한은
여전히 차량 수다. 룰의 파라미터를 읽는 것이 아니라 **룰이 답하는 질문**(§6.3 `routeStopCap()`)을
하나 더 묻는다.

**처음 만든 판은 세 가지를 한꺼번에 바꿨고, 그래서 범인을 잘못 짚을 뻔했다.** 그 판(모든 하드 용량
축의 max · **가장 작은** 차량급 기준 · **클러스터 수 상한 제거**)은 `large` 에서 −14.52% →
−12.87% 로 DoD 에서 멀어졌다. 셋을 갈라 재 보니 손해는 **상한 제거**였다 — 그 판은 `peak` 에서
클러스터를 **121개**(차량 88대) 만들었고, 남는 것이 이미 실은 차에 얹혀 권역 교차 293 → 392 ·
거리 +460 km · **+1,507,476원**이 됐다. **stop 축만 더한 최소 정정은 네 데이터셋을 모두 이긴다.**
묶어서 바꾸면 무엇이 범인인지 알 수 없다.

**2·3단계를 다른 것으로 바꾼 전략이 있다 — 그래도 1·4·5·6단계는 같다** (2026-09-17,
[ADR-042](adr/ADR-042-savings-merges-are-class-aware.md)). `savings-cw+ls` 는 「자르고 붙인다」
대신 「이어 붙이며 키운다」로 라우트를 만들고(Clarke-Wright savings), 차량은 라우트가 다 만들어진
뒤에 붙인다. 붙이는 규칙은 3단계와 같다 — 넣지 못한 stop 수 → 한계비용 → 능력이 적은 차
([ADR-031](adr/ADR-031-least-capable-first-tie-break.md)) 위에서 좌석 예약의 문
([ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md))이 묻는다. **4단계는 돌지 않는다** —
구성이 이미 순서를 정했고, 배정이 그것을 덮어쓰면 §6.9 의 비교가 두 가지를 섞는다. 순서를 고치는
것은 5단계다. 붙일 차가 없는 라우트는 미배정으로 내려가 재삽입이 stop 단위로 다시 본다(클러스터를
반으로 쪼개던 자리가 여기서는 재삽입이다).

### 6.6 전략 플러그인 구조

```java
public interface DispatchStrategy {
  String name();
  PlanResult plan(PlanningProblem problem);      // 예산은 problem 안에 있다
}
```

예산을 인자로 또 받지 않는 이유(2026-09-05, Phase 3-2.5): `PlanningProblem` 이 이미
`PlanningBudget` 을 들고 있다(§6.2). 인자를 둘로 두면 **서로 다른 예산 두 개**를 넘길 수 있고,
그러면 어느 쪽이 이기는지가 구현마다 달라진다.

| 전략 | 구성 | 용도 |
|---|---|---|
| `baseline-nn` | 클러스터링 없이 NN만 | 벤치마크 기준선 |
| `sweep-greedy-nn` | §6.5의 1~4단계 | fast mode 기본 |
| `sweep-greedy-nn+ls` | §6.5 전체 (2-opt/Or-opt/relocate) | **기본 전략** |
| `savings-cw+ls` | Clarke-Wright savings로 라우트 구성 후 LS | 비교 전략 — **네 데이터셋에서 더 싸고 `peak` 에서 예산에 잘린다** ([ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md)) |
| ~~`timefold`~~ | Timefold Solver(Community) VRPTW 모델 | **등록하지 않는다** ([ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)) — 다시 여는 조건 셋은 그 ADR 결정 3 |

전략은 `PlanRunner`가 `strategy` 파라미터로 선택하며 기본값은 설정 `dawnline.dispatch.plan.default-strategy`. 새 전략 추가는 인터페이스 구현 + 등록만으로 가능해야 한다.

**위 표의 마지막 줄이 예약하던 자리는 닫혔다** (2026-09-18, Phase 4 마감,
[ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)). 이 문서는 자체
휴리스틱을 고르면서 「비교 대상이 필요하다」고 적었고 그 대상이 `timefold` 였다. Phase 4 는 그
실험 대신 **세 가지 다른 근거**를 만들었다 — §6.9 의 **고정비 하한 열**(상시, 실행마다 나온다 ·
[ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)), **그림자 계측 원장 여덟
줄**([리포트 §7.1](benchmarks/phase4-strategies.md)), 그리고 **구성 계열이 다른 두 전략**의 같은
조건 비교(위 공정성 셋). 다른 솔버와의 비교는 *그 솔버가 얼마나 좋은지에 의존하는 상대값*이지만
완화 하한은 **어떤 계획도 그 아래로 갈 수 없다**는 절대적인 문장이다.

> **대신 이 결정은 자기 한계를 적는다** — 그 열이 재는 것은 목적함수 다섯 항 중 **고정비
> 하나**다([ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)). 나머지 넷에는
> 경계가 없고, 그래서 ADR-004 는 **다시 여는 조건 셋**을 미리 적었다(시간비가 지배하는 레짐 ·
> 라우트 구조를 바꾸는 제약 · 분 단위 예산). Phase 7-6 에 여유가 있으면 `medium` 한 개만,
> 공정성 셋을 맞춘 채로 돌린다 — 맞지 않으면 리포트에 싣지 않는다.

**`savings-cw+ls` 의 구성은 제약 조합을 안다** (2026-09-17,
[ADR-042](adr/ADR-042-savings-merges-are-class-aware.md) · [측정](benchmarks/phase4-savings-cw.md)).
위 표의 한 줄 — 「Clarke-Wright savings로 라우트 구성 후 LS」 — 이 말하지 않은 것이 셋이었다.

1. **쌍은 K-최근접 안에서만 만든다.** 완전한 savings 목록은 `peak` 에서 3,500만 쌍이다. 표는
   개선 단계가 쓰는 것과 **같은 표·같은 K**(`Neighborhood.DEFAULT_K` = 20,
   [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md))다 — 두 단계가 다른 표를
   쓰면 §6.9 의 비교가 「구성 방식의 차이」가 아니라 「표 크기의 차이」를 잰다. **K 는 전략 이름에
   넣지 않는다**(측정 문서가 적는다).
2. **병합 가능성은 「합집합 조합을 덮는 차량 중 가장 큰 것」으로 잰다.** CW 는 stop 을 이어 붙이며
   라우트의 제약 조합을 키운다 — 냉장 stop 하나가 이웃 100개와 병합되면 그 라우트 *전체*가
   냉장차를 요구한다. 「가장 큰 차」로 재면 아무 차도 못 싣는 라우트를 만들고, 거기 붙은 일반 stop
   까지 함께 미배정이 된다([ADR-033](adr/ADR-033-constraint-classes.md) 이 통합 키에서 막은 것과
   같은 일).
3. **[ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) 의 집계 불변식이 구성 단계로
   간다.** 좌석 예약은 *배정* 단계의 장치인데 CW 에서 배정은 라우트가 다 만들어진 뒤에 온다 —
   그때는 예약이 고칠 것이 없다. 그래서 「조합 c 이상을 요구하는 라우트들의 stop 합 ≤ c 를 덮는
   차량들의 슬롯 합」을 병합 판정에 건다. 상한을 말하는 룰이 없으면 이 축도 없다(같은 ADR 결정 2).

**그리고 구성은 두 단계다 — 근사는 첫 단계의 것이다** (2026-09-17,
[ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) ·
[측정](benchmarks/phase4-endpoint-merges.md)). 위 1번의 K-최근접은 **stop 이 8,411개일 때**
필요한 근사다. 1단계가 끝나면 라우트는 `peak` 에서 216개뿐이고, 라우트를 뒤집지 않으므로 이을
자리는 **꼬리 → 머리**뿐이다 — 46,090 쌍이라 근사할 이유가 없다. **2단계는 그 쌍을 전부 본다**
(게이트는 1단계와 같고, 고정점까지 돈다).

근사를 유지하면 무엇을 잃는지가 수치로 나왔다: 1단계만 돌면 `peak` 라우트 216개 중 **67개가 stop
하나짜리**이고(끝점의 최근접 20개가 이미 같은 라우트에 들어간 stop 들로 채워진다) 128개가 부착에서
밀린다. 2단계를 붙이면 **216 → 90**(차량 88대), 밀리는 라우트는 2개다. **그리고 2단계가 더 싸다**
— 끝점 전부가 38 ms 인데 이웃 표를 K=100 으로 넓히는 것은 206 ms 다(`O(n²)`). 쌍 예산은
`R(R−1) ≤ n·K` — 1단계보다 많이 만들지 않는다.

**뒤 단계는 공유한다** — 재삽입([ADR-028](adr/ADR-028-unassigned-policy.md))과 국소 탐색은 같은
클래스다. 공정성의 조건 셋(같은 예산 · 같은 이웃 표 · 같은 재삽입·개선)이 코드로 지켜져야 비교표가
구성 방식을 잰다. **라우트를 뒤집지 않는 것**도 같은 이유다 — 순서를 바꾸는 것은 5단계의 일이다.

**그리고 표는 기본 전략을 아직 바꾸지 않았다 — 두 번, 다른 이유로** (2026-09-17,
[ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) ·
[측정](benchmarks/phase4-savings-cw.md) §4.3 · [측정](benchmarks/phase4-endpoint-merges.md)).

**첫 번째 이유는 재현성이었고 해소됐다.** `savings-cw+ls` 는 `peak` 에서 30초 예산에 잘렸고 잘린
값이 실행마다 달랐다(20,940,782 ~ **23,277,318**). 넷째 실행은 마감이 개선이 아니라 **부착·재삽입을
끊어** 96 stop 이 `plan-deadline` 로 남은 것이었다.

> **마감이 무엇을 끊는지가 총비용이 얼마나 나쁜지를 정한다.** 개선을 끊으면 계획은 이미 있고 덜
> 좋을 뿐이지만, 배정을 끊으면 계획에 **구멍이 남는다**([ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md)).

바꾸는 조건을 **미리, 그리고 구조로** 적었다 — 「`peak` 구성 라우트 수 ≤ 차량 수 × 1.2(= 106)」.
벽시계 조건(「30초 안에 수렴」)은 기계가 바뀌면 참·거짓이 바뀌는데, 이 문서가 「예산이 물린 실행은
재현 대상이 아니다」라고 한 것과 같은 이유로 **전환 조건도 재현되는 것 위에 서야 하기** 때문이다.
[ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) 가 그 조건을 만들었다: 라우트
216 → **90**, `peak` 이 **13,164 ms 에 수렴**하고 기본 전략보다 **772,025원 싸다**.

**두 번째 이유는 `small` 이다.** 같은 변경이 `small` 에서 1,073,363 → **1,262,833**(기본 전략보다
**+13.4%**, 미배정 0 → 3)을 만들었다. 구성이 라우트를 17 → 8 로 줄이면 부착이 5개를 받고 3개가
통째로 재삽입으로 내려가는데, **작은 데이터셋에서는 계획을 정하는 몫이 재삽입에서 구성으로
옮겨간 것 자체가 손해다.** 남은 전환 조건은 「`small` 에서 `savings-cw+ls` ≤ 기본 전략」이고,
그것을 만드는 항목이 4-20 이다.

**`sweep-greedy-nn` 과 `sweep-greedy-nn+ls` 는 한 클래스다** (2026-09-09, Phase 4-1). 개선 단계를
붙였는지만 다르고 1~4단계는 같은 코드다 — 둘로 나누면 §6.9 의 비교표가 "개선 단계의 값어치" 가
아니라 "두 구현의 차이" 를 재게 된다. 같은 이유로 §6.7 의 FAST 모드도 이 자리를 끄는 것으로
표현된다. 설정 기본값은 2026-09-09 에 `sweep-greedy-nn+ls` 로 옮겼다 — 이 표가 처음부터 그렇게
적고 있었고 코드의 기본값만 따라오지 않았다.

### 6.7 성능 목표, 병렬화, 시간 예산, 열화 모드

| 항목 | 목표 (8코어 노트북, Docker Compose) | 측정 방법 |
|---|---|---|
| 웨이브 5,000 주문 / 40 차량 계획 시간 | p95 ≤ 30초 (기본 전략) | `dawnline_plan_duration_seconds{strategy}` — 참조 기계의 벤치마크가 **기록**하는 사실이다. CI 게이트는 시간이 아니라 **종료 사유**(`termination=converged`)를 넉넉한 예산(120초)에서 본다 — 「이 문제에서 알고리즘이 수렴한다」가 지킬 성질이고, 운영 예산을 주면 느린 러너에서 그것이 러너 속도와 섞인다(2026-09-24, §6.9 규칙 2) |
| 같은 웨이브의 **영속화** 시간 | ≤ 3초 | `dawnline_plan_persist_seconds` — **목표이지 게이트가 아니다**(2026-09-24). CI 게이트는 시간이 아니라 영속화의 **구조**(flush 횟수·세션 적재 엔티티)를 센다 — §6.9 규칙 2, [ADR-029 후속 정정](adr/ADR-029-optimizer-io-is-bulk-not-orm.md) |
| 같은 조건 fast mode | ≤ 5초 | 동일 |
| 메모리 | 계획 1회 힙 증가 ≤ 1 GB | JFR/actuator |
| 베이스라인 대비 총비용 | ≥ 15% 절감 | benchmark 리포트 — `large` **−18.20% (`savings-cw+ls`, 수렴)** · 기본 전략은 −14.93% ([ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md)). 2026-09-17 [ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) 로 −19.30% → −18.20% 로 **내려갔다** — 같은 변경이 `peak` 을 수렴시켰다([측정](benchmarks/phase4-endpoint-merges.md) §5). **Phase 4 마감 수치는 [phase4-strategies.md](benchmarks/phase4-strategies.md) §1·§8** 이다 |
| 미배정률 (정상 용량) | ≤ 0.5% | plan 메트릭 |

- **병렬화**: Kafka 리스너·DB I/O는 가상 스레드. 캠프 간 계획은 파티션(campId)별로 자연 병렬.

  **계획 *안*의 병렬 단위는 클러스터가 아니다** (2026-09-11 정정, [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) · [측정](benchmarks/phase4-where-the-time-is.md)).
  원래 문장은 「클러스터별 시퀀싱·개선은 독립이므로 `ForkJoinPool` 로 병렬 실행」이었는데,
  코드를 쓰기 전에 쓴 문장이고 두 군데에서 성립하지 않는다 — **클러스터는 독립이 아니고**
  (3단계가 하나씩 붙이며 차량 상태를 바꾼다, 시퀀싱도 클러스터가 아니라 (클러스터 × 차량)
  시험 배치마다 돈다), **개선은 클러스터 단위가 아니다**(시간의 88%가 라우트 *쌍*을 보는
  relocate·swap 이고, 클러스터별로 자르면 그 88%가 사라진다).

  | `large` 5,773 ms 의 분해 | 시간 | 비중 | 병렬 단위 |
  |---|---:|---:|---|
  | 1·2 통합·클러스터링 | 19 ms | 0.3% | — |
  | 3 탐욕 배정 | 1,548 ms | 26.8% | 한 클러스터 안의 **차량 후보** |
  | 5 개선 — 라우트 안 | 444 ms | 7.7% | **라우트** |
  | 5 개선 — 라우트 사이 | 3,680 ms | 63.7% | 라우트 **쌍** 스캔(투기적) |

  그리고 **병렬화를 만들 이유는 이 표가 만들지 않는다.** `large` 는 예산의 19%만 쓰고 수렴한다 —
  코어를 더 줘도 같은 답을 더 빨리 낼 뿐이다. 그래서 게이트를 먼저 돌렸다(`peak` 15,000/60,
  30초 대 120초): **예산은 물지만 잘림의 대가가 +0.83%** 라 기준 1% 미만 —
  **계획 내 병렬화는 이월한다**([측정](benchmarks/phase4-peak-gate.md)). 그리고 `peak` 에서는
  병렬화가 표적을 못 맞춘다 — 계획 시간의 **61%가 재삽입**이고 개선은 29.7%다.

- **마감은 계획 전체의 것이다** (2026-09-12 수정, [ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md)).
  `totalMs` 는 계획의 예산으로 쓴 낱말인데 구현은 §6.5 5단계에만 걸려 있었다. `overload` 가 그것을
  드러냈다 — 개선 단계는 13,841 ms 를 받아 12,859 ms 를 쓰며 **예산을 정확히 지켰는데 계획은
  43.2초**였고, 시간의 **61%가 마감 없는 재삽입**이었다. 이제 **배정·재삽입·개선이 같은 마감
  아래** 돌고, 마감이 오면 남은 것은 **`plan-deadline` 사유의 미배정**으로 끝낸다 — 「시도하지
  못했습니다」는 정직한 답이고 「실을 차가 없습니다」는 거짓말이다(§6.3).

  이것이 없으면 **열화 사다리가 허구다**: FAST 가 §6.5 5단계를 통째로 꺼도 `overload` 의 43.2초는
  30.4초가 될 뿐 30초가 되지 않는다. [ADR-028](adr/ADR-028-unassigned-policy.md) 의 「재삽입은 값싼
  탐욕」은 *실행 가능한 문제에서만* 참이었고(`large` 34 ms → `overload` 26.5초), 그것을 참으로
  만드는 것이 이 마감이다.

  | | 총비용 전 → 후 | 계획 시간 전 → 후 | 종료 |
  |---|---|---|---|
  | `small`·`medium`·`large`·`peak` | **한 자리도 안 바뀜** | 그대로 | 수렴 |
  | `overload` | 147,956,375 → 163,608,882 (+10.6%) | 43,233 → **30,014 ms** | 마감 |

  실현 가능한 데이터셋이 안 바뀌는 것이 **「설계 부합 수정이지 재기준이 아니다」의 증거**다.
  베이스라인은 §6.9 게이트 규칙 1 로 동결돼 있어 마감의 대상이 아니다 — 동결의 대가이고,
  마감은 기본 전략을 *나쁘게* 만들 뿐이라 비교를 유리하게 만들지 않는다.
- **비교 전략 하나가 `peak` 에서 예산을 넘는다 — 그래서 기본이 되지 못했다** (2026-09-17,
  [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) ·
  [측정](benchmarks/phase4-savings-cw.md)). `savings-cw+ls` 는 `large`(목표 행의 크기)에서
  **p95 4,654 ms** · FAST **2,038 ms** 로 두 목표를 여유 있게 통과하지만, `peak`(15,000/88)에서는
  30초에 잘린다(60초 예산에서 **32,883 ms 수렴**). 잘린 값이 20,940,782 ~ 23,277,318 사이에서
  흔들리고 그 폭이 두 전략의 차이보다 크므로, **그 위에서는 결정을 낼 수 없다**(§6.9 재현 조건).
- **시간 예산**: `PlanningBudget(totalMs, perRouteMs)`. 기본 30초. 개선 예산은 `(총예산 − 앞 단계가 쓴 시간) × 개선예산계수`다 — **클러스터 수로 나누지 않는다**([ADR-034](adr/ADR-034-degrade-mode.md) 가 대체했고 [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) 3번이 문장을 걷어냈다: 라우트 사이 이동은 어느 클러스터에도 속하지 않아 배분할 주체가 없다).
- **30초는 알고리즘 예산이지 트랜잭션 예산이 아니다.** 위 표의 두 행이 재는 것이 다르다 — `planDurationMs` 는 `RunPlanService` 의 `startedAt`–`finishedAt`(후보 조회 + 룰 + 최적화 + 검증)이고, 영속화는 그 뒤의 라우트·stop·설명 저장과 outbox 기록이다. 둘을 한 수치로 합쳐 보고하면 예산이 알고리즘 예산이 아니라 ORM 예산이 된다 — 2026-09-07 에 실제로 그랬다. 왕복 26.8초 중 20.2초(75%)가 영속화였고, 그래서 "예산 안에 끝났다" 는 통과가 최적화에 대해 말해 주는 것이 거의 없었다([ADR-029](adr/ADR-029-optimizer-io-is-bulk-not-orm.md), [측정](benchmarks/phase4-plan-roundtrip-breakdown.md)).
- **열화(degrade) 모드**: `wave.closed` 소비 지연(consumer lag) > 3 웨이브 또는 직전 계획이 예산의 80% 초과 → 다음 계획은 자동 `mode=FAST`(개선 단계 생략). 메트릭·로그로 노출, 운영자가 수동 재계획 가능. 이것이 "성수기에도 정시"를 위한 명시적 트레이드오프다. **구현은 2026-09-10, [ADR-034](adr/ADR-034-degrade-mode.md) · [측정](benchmarks/phase4-fast-mode.md).**

  **열화는 사다리다** (2026-09-10 후속 정정). 두 조건은 다른 것을 말하므로 처방도 다르다 —
  §6.7 의 예산 조건은 [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md) 의 패스
  단위 예산이 생기기 **전에** 쓰인 문장이고, 예산이 이미 계획 시간을 상한으로 묶는 지금
  「직전 계획이 예산에 가까웠다」는 과부하가 아니라 **「개선 단계가 예산을 다 썼다」**일 뿐이다.
  **처리량 부족을 말하는 신호는 랙 하나다.**

  | 단 | 조건 | 처방 | `large` 계획 p95 | 대가 |
  |---|---|---|---:|---:|
  | 아래 | 직전 계획 > 예산 × 0.8 | 다음 계획의 **개선 예산 × 0.5** (모드는 `FULL`) | 4,898 → **3,208 ms** | **+1.35%** |
  | 위 | 파티션 랙 > 3 웨이브 | 개선 단계를 **끈다** (`FAST`) | → **1,542 ms** | **+9.39%** |

  둘을 같은 처방(FAST)으로 묶었던 것이 **45배 비싼 처방**이었다 — 예산으로 개선을 끊는 대가는
  +0.21%, FAST 의 대가는 +9.4%다. 아랫단은 아낀 시간당 대가가 **4배 싸다**(56원/ms 대 232원/ms).
  기본 30초 예산에서는 계수가 **아무것도 하지 않는다**(계수 1.0 과 0.5 의 결과가 한 자리도
  다르지 않다) — 개선 단계가 「개선 폭 < 0.1%」로 먼저 멈추기 때문이고, 그래서 아랫단은 예산이
  *실제로* 조일 때만 작동한다. 계수는 `PlanningProblem.budgetFactor` 로 들어가 **개선 예산**
  (`budget.total() − 앞 단계가 쓴 시간`)에만 곱한다.

  **4-5 가 전 데이터셋으로 다시 쟀다** (2026-09-18, [측정](benchmarks/phase4-strategies.md) §4).
  위 표는 2026-09-11 의 수(예산 5초)이고 **근거는 그대로지만 자리는 옮겨졌다** —
  [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) ·
  [ADR-040](adr/ADR-040-priority-boost-decays-in-time.md) ·
  [ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md) 이 개선 단계의 일을 줄여서
  **5초 예산에서는 아랫단 조건이 켜지지 않는다**(`large` FULL 이 3,453 ms = 예산의 69% 로 수렴).
  켜지는 첫 예산은 **4초**이고 대가는 **+1.06%**(8,147,294 → 8,233,533)다. 윗단의 대가는
  `large` **+13.08%**(30초 예산)인데 **전략마다 다르다** — 같은 자리에서 `savings-cw+ls` 는
  **+4.29%** 다(개선 단계에 기대는 정도가 곧 FAST 의 대가다). 아낀 시간당으로는 아랫단
  **67원/ms** 대 윗단 **401원/ms** 로 **사다리의 순서는 유지된다.**
  다만 위 문단의 「기본 30초 예산에서는 계수가 아무것도 하지 않는다」는 **`peak` 에서는 참이
  아니다** — 계수 0.5 가 21,509,847 → 21,545,798(+0.17%)이고 그 회차는 마감에 **잘린다.**

  | | FULL | FAST | |
  |---|---:|---:|---|
  | `large` 계획 p95 (예산 30초) | 5,872 ms | **1,569 ms** | 3.7배 (fast 목표 5초의 31%) |
  | `large` 총비용 | 8,276,130 | 9,053,096 | **+9.39%** — 이것이 포기하는 값이다 |

  - **FAST 가 생략하는 것은 §6.5 5단계 하나다.** 재삽입은 개선이 아니라 값싼 탐욕이라 FAST 에서도 돈다([ADR-028](adr/ADR-028-unassigned-policy.md)). 1~4단계는 계획이 *존재하기* 위한 단계라 생략할 수 있는 것이 아니다. **전략 이름은 바뀌지 않는다** — 이름은 「무엇을 쓰려 했는가」(§6.6), 모드는 「무엇을 포기했는가」(§6.7)이고, 접으면 `dawnline_plan_duration_seconds` 의 두 라벨이 같은 말을 두 번 한다.
  - **조건 둘은 중복이 아니라 역할 분담이다.** 랙은 **선행**(웨이브가 몰린 순간 보인다), 직전 계획 시간은 **후행**(한 계획이 이미 늦은 뒤에 보인다). 랙 조건이 없으면 §8.2 의 버스트를 놓친다. 진동은 **아랫단에만** 있고(계수 0.5 의 계획이 예산의 64% 로 끝나 임계 아래로 내려온다) 진폭이 +1.35% 라 평균 +0.78% 다 — 그 진동이 평균 계획 시간을 4,898 → 4,053 ms 로 사 온다. 윗단(FAST)은 랙이 남아 있는 동안 유지되므로 진동하지 않는다. 히스테리시스 임계를 따로 두지 않는 이유가 이것이다.
  - **랙은 `Consumer#currentLag`(KIP-695)로 잰다.** Micrometer 게이지를 읽지 않는다 — **제어 입력을 관측 지표에서 읽으면** 지표 이름이 바뀔 때 `NaN` 이 「랙 없음」으로 읽혀 판단이 조용히 멈춘다. 아래 §9.1 의 "Kafka 소비자 랙은 기본 지표 사용" 은 **보는** 용도다. 값은 **파티션** 단위이므로 「이 캠프의 랙」이 아니라 **「이 캠프가 실린 소비 흐름의 랙」**이다(campId 키라 같은 캠프는 언제나 같은 파티션이지만, 파티션 수 < 캠프 수라 역은 아니다).
  - **모름은 0이 아니다.** 랙을 볼 수 없는 경로(운영자 재실행·정체 회수·리밸런스 직후)에서 `null` 을 0 으로 접으면 랙 조건이 조용히 「아니오」가 된다. 사유 다섯 — `REQUESTED`(사람이 정했다, **열화 아님**) · `LAG` · `BUDGET` · `LAG_UNKNOWN`(FULL 이지만 조건 하나를 못 봤다) · `NONE`.
  - **사유는 계획 행에 남는다**(`route_plans.mode_reason`, V5). 카운터 라벨은 집계지 개별 답이 아니고, "이 웨이브는 왜 FAST 였나" 는 §6.3 이 라우트에 "왜 이 차인가" 를 남기게 한 것과 같은 요구다.
  - **직전 계획은 DB 에서, 캠프별로** 읽는다. 인메모리 홀더는 재기동에 사라지고 인스턴스마다 다르다. 큰 캠프의 느린 계획이 작은 캠프를 열화시키면 이 신호가 「이 캠프가 밀린다」가 아니라 「어딘가 바쁘다」를 뜻하게 된다. 조회 비용은 10만 행에서 **5.4 ms**(계획 시간의 0.09%)라 인덱스를 넣지 않았다 — 판단과 재검토 조건은 [측정](benchmarks/phase4-fast-mode.md) §5.
  - **열화는 래치가 아니다.** 상태를 들지 않고 매 계획마다 두 사실을 다시 본다. 조건이 사라지면 다음 계획이 곧바로 FULL 이라 "한 번 열화하면 누가 되돌리는가" 라는 질문이 없다.
  - 임계는 설정이다: `dawnline.dispatch.degrade.max-backlog-waves`(3) · `budget-ratio`(0.8) · `budget-factor`(0.5).
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

**입력은 자기 DB 다 — 페이로드는 트리거다**
([ADR-048](adr/ADR-048-replan-reads-its-own-db.md), 2026-09-23).
위 1단계의 「미완료 stop」은 <em>어느 stop 이 남았나</em>만 말한다. 다시 푸는 데는 그것만으로
모자란다 — **기사가 지금 어디에, 얼마나 늦게 있나**가 있어야 남은 구간의 도착 시각이 나오고,
그래야 지각 페널티가 나오고, 그래야 「옮기면 나아지는가」에 답할 수 있다. 소속은 5-5 가 채운
`route_stops.status` 가 말한다([ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md)).
**시각도 같은 테이블에서 읽는다**:

- `route_stops.actual_at` — 그 stop 에 **처음 닿은** 시각. `delivery.status` 의 `occurredAt` 을
  5-5 의 전이가 함께 적는다(계약은 이미 싣고 있었다). `ARRIVED`·`COMPLETED`·`FAILED` 중 먼저 온
  것이 쓰고 **덮어쓰지 않는다** — 덮으면 이 값은 도착이 아니라 완료가 되고, 아래 편차가
  「얼마나 늦게 도착했나」에서 「거기서 머문 시간까지 더한 값」으로 조용히 바뀐다.
- **편차 = 마지막으로 닿은 stop 의 `actual_at − planned_arrival`.**
- **닿은 stop 이 없으면 편차는 «모름» 이고, 모름은 0 이 아니다** — 재계획하지 않고
  `dawnline_replan_total{outcome=no-anchor}` 로 센다. 출발 지연만으로 난 at-risk 가 그 자리이고,
  첫 `ARRIVED` 뒤 tracking 의 5분 쿨다운이 다시 발화하므로 구멍은 **stop 하나 뒤에 닫힌다**.

`delivery.at-risk` 의 `deviationSeconds` 는 **입력이 아니라 대조값**이다. 자기 값과 60초 넘게
갈리면 `dawnline_at_risk_deviation_mismatch_total` 을 올린다 — tracking 과 dispatch 가 같은
라우트를 다르게 보고 있다는 뜻이고, 그 신호가 없으면 갈리기 시작한 순간이 어디에도 나타나지
않는다(§9.1 의 두 relocate 카운터와 같은 형식이다 — <em>둘이 갈리는 것이 정보다</em>).
페이로드에서 편차를 읽으면 「진실 하나」가 **소속은 dispatch · 시각은 tracking** 으로 갈린다.

**`route:{id}:progress`(§7.2)는 여기서 읽지 않는다** (2026-09-23, 구현에서 정정). 5-5 가 그
캐시를 채우며 「첫 소비자는 5-3」이라고 적어 두었지만, 실제로 재계획이 필요한 것은
`nextSeq`·`completed`·`failed` 세 칸이 아니라 **`actual_at` 과 `planned_arrival`** 이다 — 편차가
거기서 나오기 때문이고(위 문단), 그 값은 캐시에 없다. 같은 행을 어차피 읽으므로 캐시를 먼저
보는 것은 조회를 아끼지 않고 <em>같은 사실의 두 번째 출처</em>만 만든다. 캐시가 진실이 될 수
없다는 것은 불변규칙 7 이 이미 정했고, 아끼지도 못하면 남는 것은 갈라질 자리뿐이다.
**그러면 이 캐시는 쓰는 쪽만 남았고, 그래서 지웠다** (Phase 6-0c 판정, 같은 날). ops 의 읽기
모델(§5.5)은 이벤트로 프로젝션하지 dispatch 의 Redis 를 읽지 않고, `GET /routes/{routeId}` 가
stop 마다 살아 있는 상태를 이미 돌려주므로 위임 조회도 그 캐시를 필요로 하지 않는다.
근거와 지운 목록은 §7.2 표 아래에 있다.

**편차는 «평가 시계» 를 민다 — 저장되는 `planned_arrival` 은 계획 시계 그대로다.**

| | 시계 | 쓰는 곳 |
|---|---|---|
| 평가 | `계획 시작 + 편차` | 「옮기면 총비용이 주는가」, 하드 룰 재검증 |
| 저장 | `계획 시작` | `route_stops.planned_arrival`·`planned_departure` 재작성 |

편차를 저장까지 반영하면 **다음 편차의 기준선이 사라진다** — `actual_at − planned_arrival` 에서
빼는 쪽이 방금 `actual_at` 으로 밀렸기 때문이고, 그러면 두 번째 at-risk 에서 dispatch 는 자기
편차를 0 으로 본다. 반대편에서 말하면 **`planned_arrival` 은 계획이고 ETA 는 tracking 의 것이다**
(`shipments.eta_at`, §5.4). 두 테이블이 ETA 를 적으면 둘은 갈라진다. 저장 시계가 계획 시계
그대로이므로 닿은 stop 들의 `planned_arrival` 은 재계획을 지나도 움직이지 않는다 — 기준선의
안정성이 위의 편차 계산을 성립시킨다.

**3단계 「`relocate` 만」이 뜻하는 것 셋** ([ADR-048] 결정 4).

1. **삽입 위치는 «현재 위치 이후» 만이다.** 마지막으로 닿은 stop 까지가 <strong>얼어 있는
   앞자락</strong>이고 그 뒤에만 넣는다. 지나간 자리에 넣으면 기사가 이미 떠난 지점으로 돌아가는
   계획이 나온다. **빼는 쪽도 같다** — 앞자락의 stop 은 옮길 수 없다.
2. **[ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) 의 제약 조합 게이트가 여기에도
   걸린다.** 냉장 ∧ 위험물 stop 은 그 조합을 싣는 차량으로만 간다. 재계획이 게이트 밖에 있으면
   §6.5 3단계가 좌석으로 지킨 것을 이 절이 뒷문으로 푼다. 구현은 별도 코드가 아니라 **두 라우트를
   `RuleSet` 으로 다시 쌓는 것**이고, 그 안에 `VEHICLE_ATTRIBUTE_MATCH` 가 들어 있다.
3. **원 라우트와 대상 라우트 둘 다 재검증하고 둘 다 `revision` 을 올린다** — §5.3 운영자 재배정과
   같은 경로다. 떠난 쪽은 짐이 줄어 하드 룰을 어길 수 없지만 시간이 당겨져 지각 판정이 바뀌고,
   그 사실이 발행에 실려야 한다.

2단계의 **미출발 차량으로 옮기는 것은 새 라우트를 여는 일**이라 고정비가 든다. 비용식에 따로
더하지 않는다 — `CostModel.routeCost` 는 `stopCount > 0` 일 때만 고정비를 물므로(§6.4), 빈
라우트가 첫 stop 을 받는 순간 그 비용이 총합에 **저절로** 나타난다.

**탐색에는 상한이 있다.** 한 번의 재계획이 옮기는 stop 수와 평가 횟수에 상수 상한을 둔다 —
「대규모 재편 금지」가 성능의 문장이기도 하기 때문이다. 상한에 닿는지는 [ADR-048] 재검토 지점 3 이다.

**결과는 outcome 으로 갈린다 — 실패해도 DLQ 로 보내지 않는다.** `dawnline_replan_total{outcome}`
의 다섯 갈래는 `applied` · `cooldown` · `no-anchor` · `no-candidate` · `no-gain` 이다(§9.1).
DLQ 로 보내면 <em>고칠 수 없는 것</em>이 재시도된다 — 「후보가 없다」는 재시도로 달라지지 않고,
사람이 열어도 할 일이 없다. **`no-gain` 의 기준은 소프트 룰까지 포함한 두 라우트의 총비용**이다:
지각 페널티가 줄어도 거리·시간이 더 늘면 옮기지 않는다(§6.1 목적함수 그대로).

**`applied` 는 설명을 남긴다.** `plan_explanations` 에 `rule_name = 'AT_RISK_RELOCATE'`,
`outcome = ASSIGNED`, `detail` 에 「어느 주문이 어느 라우트에서 어느 라우트로, Δ비용 얼마」를
주문 한 건에 한 행씩. §6.3 이 룰을 데이터로 둔 이유가 「왜 이 주문이 이 차인가」에 답하기
위해서였고, **운영자가 그것을 가장 많이 묻는 자리가 재계획이다** — 기사에게서 전화가 오는
자리이기 때문이다. 최초 계획에만 설명이 있으면 그 물음의 답은 「계획 때는 A 차였습니다」로 끝난다.

**5단계의 쿨다운은 `routes.last_replanned_at` 이고, 재계획 트랜잭션 안에서 비교·갱신한다**
([ADR-046](adr/ADR-046-at-risk-is-an-event.md) 결정 3). tracking 의 Redis 쿨다운은 **알림 수**를
지키지 정확성을 지키지 않는다 — Redis 가 죽으면 중복 at-risk 가 나가고(§7.2 가 허용으로 정한
폴백), 두 at-risk 는 `eventId` 가 달라 `processed_events` 가 막지 못한다. **「쿨다운은 이미
있으니 됐다」가 이 자리의 함정이다.**

### 6.9 벤치마크 방법

- 데이터셋: `tools/benchmark/datasets/` — `small`(500 주문/5 차량), `medium`(2,000/20), `large`(5,000/40), `peak`(15,000/**88**), `overload`(15,000/60), 각각 seed 고정 생성. 좌표는 서울 근사 격자(캠프 중심 반경 8 km, 밀도 불균일).
- **실현 가능성 기준과 `overload`** (2026-09-12). 수요가 용량을 넘으면 어떤 알고리즘도 미배정을 없앨 수 없고, 그 표는 라우팅 품질이 아니라 용량 부족을 잰다. 기준은 `DatasetFeasibilityTest` 가 강제하며 축은 다섯이다 — 총 중량·부피 70%, 냉장 70%, **제약 조합 80%**([ADR-033](adr/ADR-033-constraint-classes.md)), **통합 후 stop 수 ≤ 0.8 × max-stops × 차량 수**, 유효 stop 슬롯 ≥ stop 수 × 1.2.

  stop 축의 80%는 2026-09-12 에 옮겨 왔다. 그 축만 여유가 0%(「차량 수 × 120 이하」)였는데 그것은 *완벽한 패킹*을 요구하는 수이고, 같은 이유로 중량·부피에는 이미 여유를 두고 있었다 — 축 하나만 기준이 달랐다. 그리고 **`peak` 이 그 검사의 대상이 아니었다**: stop 8,411 개가 슬롯 7,200 개를 넘는 것을 아무도 보지 못했고, 총비용의 88%가 미배정 페널티인 표를 「피크 성능」으로 읽을 뻔했다([측정](benchmarks/phase4-peak-gate.md)). 검사 목록은 이제 **빼는 방식**(`EXCLUDE`)으로 적는다 — 데이터셋이 새로 생기면 자동으로 대상이 된다.

  **`overload`(15,000/60)는 그 기준을 일부러 어긴다.** `peak` 과 주문·seed 가 같고 차량만 60대라 stop 8,411 개가 슬롯의 **146%**다. 버리지 않는 이유는 그것이 실제 성수기의 질문이기 때문이다 — 「다 못 실을 때 누가 빠지고, 계획은 몇 초에 끝나는가」. 재는 것은 셋이다: **미배정 정책**(ADR-028) · **계획 시간의 상한**(마감이 없는 단계가 여기서 드러난다) · **열화**(§6.7). 실제로 셋을 다 드러냈다.

  다만 **웨이브는 (캠프, 티어, 컷오프) 단위**다(§2.2·§5.2). 그러므로 **15,000건 한 웨이브는 캠프 하루치를 통째로 한 웨이브에 넣은 것**이지 정상적인 피크 웨이브가 아니다. `overload` 를 「성수기의 정상 부하」로 읽으면 안 된다.

  §6.9 비교표는 **두 절**로 나눈다 — 실현 가능한 데이터셋(`small`·`medium`·`large`·`peak`)과 과부하(`overload`). 같은 절에 섞으면 표가 재는 것이 전략이 아니라 데이터셋이 된다. 회귀 게이트는 `medium` 그대로다.

  **벤치마크 데이터셋의 규모는 데모 시드의 캠프와 다르다.** 부록 A 의 캠프는 20대(+야간조 8대)인데 `peak` 은 88대다 — `peak` 은 「한 캠프의 성수기」가 아니라 **`large` 의 3배 규모 웨이브를 그 규모에 맞는 용량으로 푸는 것**이다. 차량 수는 고른 값이 아니라 실현 가능성 기준이 정한 최소 대수이고, 그래서 데이터셋이 커지면 대수도 따라 커진다. `overload` 를 「캠프 하루치를 한 웨이브에」로 적은 것과 같은 종류의 문장이다 — 둘 다 **이 표가 무엇을 재는 것이 아닌지**를 말한다.
- 지표: 총비용, 총거리, 계획 시간, 미배정 수, 지각 stop 수·평균 지각분, 차량 사용 대수.
- 각 전략 × 데이터셋을 5회 반복, 중앙값·p95 기록. 결과는 `docs/benchmarks/phase<N>-<주제>.md` 에 표와 함께 커밋하고 README 상단에 링크한다(파일 이름은 날짜가 아니라 **Phase·주제**다 — 같은 주제를 다시 잰 문서를 나란히 놓을 수 있어야 한다). Phase 4 의 마감 리포트는 [benchmarks/phase4-strategies.md](benchmarks/phase4-strategies.md) — 다섯 데이터셋 × 네 전략 · 사다리 두 단 · 고정비 하한 · 재기준 이력 · 그림자 계측 원장.
- **최적화 항목은 구현 전에 그림자 계측으로 상한을 잰다** (2026-09-12 규칙화). 기계장치를 만들기
  전에, 그 기계장치가 **건너뛰거나 줄일 수 있었을 일의 양을** 기존 코드 안에서 세어 본다 —
  판정은 계산하되 동작은 바꾸지 않으므로 결과가 흔들리지 않고, 「그 판정이 틀린 적이 있는가」를
  같은 실행에서 함께 검사할 수 있다. 규칙이 여기 들어올 때 근거는 세 번이었다 —
  ① 병렬 단위(§6.7 분해 — 클러스터에 시간이 없었다) ② 안 훑기(쌍 건너뛰기 상한 4~17%, 잘못된
  건너뛰기 0 — 정확하지만 만들 값이 없었다) ③ `peak` 게이트(잘림의 대가 +0.83%).
  **Phase 4 가 끝날 때 여덟 번이 됐고, 여덟 번 모두 결정을 정했다(만들지 않기로 한 것이 셋).**
  원장은 [benchmarks/phase4-strategies.md](benchmarks/phase4-strategies.md) §7.1 이다.
  구현 뒤에 재면 «만들었으니 쓴다» 가 되고, 그때 측정은 결정이 아니라 변호가 된다.

  **상한이 갈리는 자리는 「판정 단위와 낭비 단위가 맞는가」다.** 같은 Phase 에서 두 기계장치의
  상한이 정반대로 나왔고, 이유는 대칭이다 — **안 훑기**(4~17%)는 무효화 단위가 **라우트 전체**
  (91~140 stop)인데 낭비 단위는 **위치 하나**였다. 이동 하나가 라우트 둘을 통째로 더럽히니 걷을
  수 있는 것이 거의 남지 않는다. **재삽입 가지치기**(93~97%)는 판정 단위가 (stop × 라우트)
  하나이고 낭비 단위도 (stop × 라우트) 하나다. **단위가 맞으면 거의 전부 걷힌다.** 그림자
  계측을 어디에 걸지 고를 때 이 물음이 먼저다.

  **상한 하나만 재면 «잰 항이 틀렸다» 를 못 본다 — 상한과 «그 상한을 실제로 걷은 값» 을 함께
  낸다** (2026-09-12 정정, 4-17 의 계측). [ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)
  이 그 실수였다: 고정비의 여유 762,000원을 상한으로 읽었는데 그 항은 **회수 대상이 아니었다.**
  4-17 에서는 상한(미배정 위험물 페널티)에 더해 **좌석을 실제로 만들어 앉히고 그 비용을 뺀 값**을
  함께 냈고, 두 값이 `large` 에서 **30,000원 대 −10,945원**으로 갈렸다 — 싣는 것이 페널티보다
  비싼 자리였고, 상한만 봤다면 없는 이득을 좇았을 것이다([측정](benchmarks/phase4-scarce-seats.md)).
  두 값 사이가 그 항목이 움직일 수 있는 폭이고, **폭이 음수를 포함하면 만들 이유가 없다.**

  **그리고 상한은 «그 항» 의 상한이지 «그 변경» 의 상한이 아니다** (2026-09-12 보강, 4-17 을
  만들고 나서, [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md)). 같은 계측이 이번에는
  **아래쪽으로** 틀렸다: 상한을 「미배정 페널티의 회수」로 잡았는데 좌석 예약은 미배정만 걷지 않고
  **배정 자체를 바꿨다.** 실제 값이 `small` 354,487(상한 350,000) · `peak` 566,528(상한 290,000)
  으로 상한을 넘었고, `peak` 의 차이는 대부분 지각 페널티였다(202,050 → 79,700원). 그러므로 상한을
  낼 때는 **그 변경이 건드리는 항을 먼저 적는다** — 어느 쪽으로 틀렸는지는 부호가 아니라 <em>항의
  범위</em>가 정한다. 폭이 음수여도 그 폭이 <em>좁게 잰 항</em>의 것이면 기각의 근거가 되지 않는다.

  **두 문장은 짝이다.** 하나는 **위로** 틀리는 경우(잰 항이 회수 대상이 아니었다 — [ADR-038]
  (adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) 의 고정비 여유 762,000원), 하나는
  **아래로** 틀리는 경우(변경이 잰 항보다 많은 항을 건드렸다 — [ADR-039]
  (adr/ADR-039-reserve-seats-by-constraint-class.md) 의 `peak` 566,528원 대 상한 290,000원).
  같은 두 항목이 양쪽 사례를 다 냈다: **상한을 낼 때는 「무엇을 회수하는가」와 「무엇을
  건드리는가」를 둘 다 적는다.**

  **셋째는 4-19 가 더했다 — 상한이 조건을 넘긴 것과 총비용이 좋아지는 것은 다른 명제다**
  (2026-09-17, [ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md)). 끝점 계측의 상한은
  **라우트 수**를 쟀고 라우트 수는 목적함수가 아니다. 조건(`peak` 라우트 ≤ 106)은 충족됐고 `peak`
  은 772,025원 싸졌는데, **같은 변경이 `small` 을 13.4% 비싸게 만들었다**
  ([측정](benchmarks/phase4-endpoint-merges.md) §5 · [마감 리포트](benchmarks/phase4-strategies.md) §6.2).
  그러므로 **상한이 무엇의 상한인지를 적을 때 「그 수가 목적함수인가」를 함께 적는다.**
- **리포트는 «불가능의 경계» 를 함께 싣는다 — 고정비 하한** (2026-09-12 규칙화,
  [ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)). 기하·시간·순서를 전부
  버리고 **총량만** 덮는 가장 싼 함대의 고정비다. 축은 **제약 조합(냉장·위험물) × 자원(stop
  슬롯·중량·부피)** 이고, 축마다 **분수 허용**으로 덮어(단위 용량당 고정비가 싼 차부터) 가장
  비싼 축을 쓴다 — 실제 해는 모든 축을 동시에 덮어야 하므로 한 축만 덮는 비용보다 쌀 수 없다.
  stop 슬롯은 룰셋이 말한 상한을 쓴다(§6.3 `routeStopCap()`).

  | | 하한 | 기본 전략 | `savings-cw+ls` | 무는 축 |
  |---|---:|---:|---:|---|
  | `small` | 208,333 | 295,000 (1.42배) | 295,000 (1.42배) | 전체 stop |
  | `medium` | 788,666 | 1,160,000 (1.47배) | 1,115,000 (1.41배) | 전체 stop |
  | `large` | 1,738,000 | 2,500,000 (1.44배) | **2,275,000 (1.31배)** | 전체 stop |
  | `peak` | 4,067,333 | 5,500,000 (1.35배) | 5,500,000 (1.35배) | 전체 stop |
  | `overload` | **실현 불가** | — | — | 전 차량으로도 못 덮는다 |

  (2026-09-18 갱신, [측정](benchmarks/phase4-strategies.md) §3. 이 열은 전략마다 다르므로 두 열로
  적는다 — **`baseline-nn` 의 배율이 더 낮은 것은 좋은 뜻이 아니다**: 하한은 *전체 stop 을 덮는*
  함대의 고정비인데 `baseline-nn` 은 덮지 않는다.)

  **왜 상시 열인가**: 동결된 베이스라인은 «다른 휴리스틱» 이지 «경계» 가 아니다. 그것만 적으면
  *최적해와 얼마나 먼가* 에 답할 자리가 없다. **정수로 올리지 않는다** — 올리면 「한 축만 본
  최적해」가 되어 다른 축과 겹칠 때 하한이 깨진다. 느슨하더라도 참인 쪽을 고른다.

  **그리고 그 열은 자기 한계를 함께 말한다 — 고정비의 하한이지 총비용의 하한이 아니다.**
  `large` 에서 고정비를 하한 근처(2,090,000)까지 민 변형은 미배정 페널티가 30,000 → 1,660,000
  이 됐고 사유는 전부 위험물이었다. **빈 좌석은 낭비가 아니라 희소 능력 수요가 나중에 앉을
  자리다.** 이 열은 「얼마나 멀리 있는가」를 말하지 「가야 한다」를 말하지 않는다.
- **재현 조건**: 커밋 SHA · 데이터셋 seed · 전략 이름 · 모드 · 개선 예산 계수, 그리고
  **「이 실행은 수렴으로 끝났다」**(2026-09-11, [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) 4번).
  예산이 물려 끊긴 실행은 코어 수 이전에 **기계가 다르면 잘리는 지점이 다르다** — 그런 실행끼리
  비교하는 것은 알고리즘이 아니라 그날의 CPU 를 비교하는 것이다. 그래서 결과 동일성을 말하는
  테스트·게이트·리포트는 수렴 종료를 **전제 어설션으로 먼저 말한다**(CLAUDE.md 의 「폴백 테스트는
  전제를 첫 어설션으로 스스로 말한다」와 같은 규칙). 전제가 조용히 무너지면 동일성 테스트는 계속
  통과하면서 아무것도 검사하지 않는다.
- 회귀 방지: CI에서 **`medium`** 을 실행해 기본 전략 **비용**이 베이스라인보다 나쁘면 실패
  (`--gate baseline-nn` → 종료 코드 1).

**게이트 데이터셋이 `medium` 인 이유** (2026-09-05 결정, 처음에는 `small` 이었다). 근거는 결과가
아니라 메커니즘이다 — 클러스터링은 "누구를 어느 차에 태울지" 의 자유도가 있을 때만 값을 만드는데,
`small` 은 차량 5대 중 최소 4대가 필요해 그 자유도가 **구조적으로 없다**(유효 슬롯 여유 1.31 대
medium 1.52 · large 1.50). `medium`(20대)이 그것이 처음 생기는 크기다. `small` 에서 기본 전략이
베이스라인보다 **+8.8%** 비싼 것은 지워지는 결과가 아니라 **제품 사실**이므로
`docs/benchmarks/phase3-baseline.md` §4-5 에 비용 분해와 함께, README 에 「알려진 레짐」으로 남긴다.

**그 격차는 2026-09-09 에 닫혔다** (Phase 4-1, [측정](benchmarks/phase4-local-search.md) §4).
개선 단계가 붙은 `sweep-greedy-nn+ls` 는 `small` 에서 `baseline-nn` 보다 **−3.9%** 다. 다만
**게이트 데이터셋은 `medium` 으로 둔다** — 위 문단의 근거는 결과가 아니라 자유도의 유무이고,
그 사실은 격차가 닫혔다고 달라지지 않는다. 결과를 보고 기준을 옮기면 다음 번에도 그렇게 된다.

**게이트 규칙 둘** (2026-09-05 확정)

1. **`baseline-nn` 은 게이트가 켜진 순간 동결된다.** 베이스라인이 좋아지면 그때까지의 비교가 전부
   무효가 된다 — 기준선이 움직이면 "나아졌다" 가 무엇에 대한 말인지 사라지기 때문이다. 바꿔야 하면
   `docs/benchmarks/` 에 **재기준(re-baseline) 기록**을 남기고 그때까지의 수치를 **새 기준으로 다시
   낸다.** 기록 없이 바꾸면 다음 사람은 두 시점의 표를 같은 축에 놓을 수 없다. 동결의 대상은
   수치가 아니라 **클래스**다 — `BaselineFrozenTest` 가 `BaselineNearestNeighbor.java` 의
   SHA-256 을 고정한다. 개선은 **새 전략으로 등록**한다(§6.6 레지스트리).
2. **게이트는 비용만 본다.** 두 전략을 **같은 실행 안에서** 돌려 비교하므로 비용 비교는 러너 사양에
   독립이다. 반면 계획 시간은 CI 러너에 따라 흔들리므로(Phase 1 k6 가 0.75 CPU 에서 본 것과 같은
   종류의 흔들림) **기록만 하고 게이트 조건에 넣지 않는다.** 환경 탓으로 빨개지는 게이트는 결국
   꺼지고, 꺼진 게이트는 없는 것만 못하다 — 있다고 믿게 만들기 때문이다.

**동결이 보장하는 것과 보장하지 않는 것** (2026-09-05). `BaselineFrozenTest` 가 얼리는 것은
`BaselineNearestNeighbor` **클래스**다. 그래서 동결은 **비교의 공정성**을 지킨다 — 두 전략이 같은
`StopMerger`·`CostModel`·거리 함수를 쓰므로, 그 공용 부품이 바뀌어도 같은 실행 안의 두 수는 여전히
대등하다. 지키지 **못하는** 것은 **절대 수치**다. 공용 부품이 바뀌면 `baseline-nn` 의 1,510,366원도
바뀐다. **소프트 룰이 바뀌면 더 그렇다** — 룰은 두 전략이 똑같이 무는 항이라 비교는 여전히
대등하지만, 목적함수 자체가 달라지므로 **전후의 절대값은 비교 대상이 아니다.**
2026-09-12 의 `PRIORITY_BOOST` 감쇠식 변경([ADR-040](adr/ADR-040-priority-boost-decays-in-time.md))이
그런 경우였고, 네 데이터셋의 `baseline-nn` 을 **함께 다시 냈다**(1,531,480 → 1,517,523 ·
4,636,549 → 4,588,065 · 9,682,478 → 9,577,578 · 28,624,728 → 28,369,930).

그래서 **리포트의 신원은 커밋 SHA · 데이터셋 seed · 전략 이름 셋**이고, 세 값을 리포트 헤더에 박는다
(`MarkdownReport`). 규칙 둘: **다른 리포트끼리 절대 수치를 비교하기 전에 커밋이 같은지 먼저 본다**,
그리고 **문서에 귀속을 적을 때는 `main` 의 커밋으로 적는다.** 후자는 2026-09-05 에 배웠다 — 브랜치
커밋을 적었더니 squash 머지가 그 커밋을 갈아치우고 브랜치를 지우면서 참조가 하루 만에 죽었다.
리포트를 낸 커밋과 그것이 `main` 에 들어간 커밋은 다르고, 나중까지 남는 것은 후자다.
같지 않으면 비교 대상은 수치가 아니라 *같은 커밋에서 다시 낸 수치*다. 작업 트리가 더러운 채로 낸
리포트는 어떤 커밋에도 귀속되지 않으므로 헤더가 그 사실을 함께 적는다.

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

**통합된 stop 의 부분 취소**는 stop 의 상태로 말할 수 없다([ADR-026 후속 정정 — Phase 3-6]).
§6.5 1단계의 `StopMerger` 가 같은 지점·같은 약속창의 주문을 묶으므로 세 주문이 실린 stop 에서
하나만 취소되는 일이 일어나고, 그때 stop 은 여전히 방문해야 해서 `status` 는 `PLANNED` 다.
그래서 `plannedStop` 에 **`cancelledOrderIds`**(optional, 기본 `[]`, `orderIds` 의 부분집합)를
둔다 — `orderIds` 에서 빼지 않는 이유는 stop 을 배열에서 지우지 않는 이유와 같고, 그 값이 필요한
곳은 `order_id` 가 PK 인 §5.4 의 `shipments` 다. 전부 취소되면 `status` 가 `CANCELLED` 가 되고
두 배열이 같아진다. 화물·서비스 시간은 다시 계산하지 않는다(`PlanPruner` 와 같은 판단).

**네 번째 행은 Phase 5 까지 발화하지 않는다.** `route_stops.status` 를 `ARRIVED`/`COMPLETED` 로
옮기는 코드가 아직 없기 때문이다. 원인은 소비자 목록이었고 **그것은 2026-09-05 에 고쳤다** —
§4.1 에서 dispatch 가 `delivery.status` 의 소비자가 됐다(그 표 아래 문단에 근거가 있다: §6.8 의
"미완료 stop 만" 과 §7.2 의 `route:{id}:progress` 도 같은 결손을 겪고 있었다 — 그 키는
2026-09-23 에 지웠고, 그래도 §6.8 과 이 분기는 그대로 남는다). 남은 것은 발행자다.
tracking 이 그 이벤트를 내는 Phase 5 에 리스너와 상태 전이가 들어가고, 그때까지
`dawnline_cancel_too_late_total` 은 구조적으로 0 이며 아래 "재검토 지점" 의 판정은 아무것도
검사하지 않는다. **0 을 "경합 창이 좁다" 로 읽으면 안 되는 기간이 여기다.**

> **그 기간은 2026-09-22 에 끝났다** (Phase 5-5, [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md)).
> dispatch 의 `delivery.status` 리스너가 `route_stops.status` 를 `ARRIVED`/`COMPLETED`/`FAILED`
> 로 옮기므로 네 번째 행의 조건이 처음으로 참이 될 수 있다. **거부 코드는 한 줄도 바뀌지
> 않았다** — `CancelOrderService` 와 `AssignedStop.visited()` 는 Phase 3 부터 그대로이고,
> 없던 것은 조건이 참이 되는 경로였다. 이제부터 0 은 미구현이 아니라 관측이다.

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
- **최적화기 I/O 경로(입력 적재·결과 저장)는 ORM 이 아니라 벌크**([ADR-029](adr/ADR-029-optimizer-io-is-bulk-not-orm.md)). 계획의 입력(후보)은 읽기 전용 프로젝션으로 읽고, 결과(라우트·stop·설명)는 JDBC 배치로 쓰며, 후보 상태 반영은 결과별 집합 `UPDATE … WHERE order_id = ANY(?)` 다. 이유는 성능 이전에 **의미**다 — 최적화기의 입력은 순수 값이고(불변규칙 5) 결과물은 쓰는 시점에 도메인 동작이 없다. 값을 관리 엔티티로 읽어 두면 그 뒤의 모든 네이티브 질의가 auto-flush 로 전수 더티 체크를 하며, 측정된 대가는 **21.9배**였다([측정](benchmarks/phase4-plan-roundtrip-breakdown.md)).
- N+1 방지: 컬렉션 로딩은 `@EntityGraph` 또는 명시 fetch join. 테스트에서 Hibernate statement 카운터로 쿼리 수 상한 검증.

### 7.2 Redis 사용 카탈로그

| 키 패턴 | 자료구조 | 서비스 | TTL | 장애 시 폴백 |
|---|---|---|---|---|
| `idem:order:{key}` | STRING | order | 24h | DB `idempotency_keys`만으로 동작 |
| `rl:customer:{id}` | HASH(Lua 토큰버킷) | order | 60s | **허용**(fail-open) + `bypassed` 메트릭·알림 |
| `geo:fc`, `geo:camp` | GEO | fulfillment | 없음(기동 시 재적재) | DB 전체 조회 후 메모리 하버사인 |
| `zone:geohash5:{p}` | STRING(`zoneId:campId`) | fulfillment | 10m | DB 조회 |
| `lock:wave:{id}` | STRING NX | fulfillment | 60s | 단일 인스턴스 가정 하 DB 낙관적 락으로 중복 방지 유지 |
| `rules:camp:{id}:v{n}` | STRING(JSON) | dispatch | 1h | DB 조회 |
| `dist:{gh7a}:{gh7b}` | STRING | dispatch(OSRM 시) | 1d | 하버사인 |
| `driver:{id}:pos` | GEO | tracking | 1h | 없음(시각화용). **아직 아무도 쓰지 않는다**(2026-09-19, Phase 5-2) — 쓰는 코드도 읽는 코드도 없고 tracking 은 `route.assigned` 의 `driverId` 를 읽지도 않는다(`RouteAssignedPayload`). 채우는 시점은 **첫 소비자가 나타날 때**, 즉 Phase 6 의 ops-web 지도다 — dispatch OpenAPI 산출물·`delivery.route-departed` 와 같은 원칙이다(「부재는 첫 소비자가 나타나는 시점에 채운다」). 그때까지 기사 시뮬레이터는 스캔마다 `lat`·`lng` 를 실어 보내 데이터가 비어 있지 않게만 한다 |
| `route:{id}:atrisk:cooldown` | STRING NX | tracking | 5m | 중복 at-risk 허용. **흡수하는 쪽은 멱등 소비자가 아니다**(2026-09-19 정정) — 두 at-risk 는 서로 다른 `eventId` 라 `processed_events` 에는 둘 다 처음 보는 이벤트다. 중복이 *재계획 두 번*이 되지 않게 하는 것은 dispatch 의 DB 쿨다운이고(§6.8 `routes.last_replanned_at`, 재계획 트랜잭션 안에서 비교·갱신), 이 키가 지키는 것은 **알림 수**다 ([ADR-046](adr/ADR-046-at-risk-is-an-event.md)). 폴백은 세어 둔다 — `dawnline_at_risk_cooldown_bypassed_total` |

원칙: Redis는 **성능·조정(coordination)** 용도이며 **유일한 진실 저장소가 아니다**. 어떤 키가 사라져도 정확성은 DB로 회복된다.

**이 표에서 행 하나가 빠졌다 — `route:{id}:progress`(HASH: nextSeq, completed, failed)** (2026-09-23,
Phase 6-0c). 5-5 가 `delivery.status` 전이의 끝에서 그 키를 채웠고 5-3 이 첫 소비자가 될
예정이었는데, 재계획이 필요한 값은 그 세 칸이 아니라 `actual_at`·`planned_arrival` 이어서
읽는 쪽이 오지 않았다([ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 결정 1). Phase 6 에서도
오지 않는다 — ops 는 이벤트로 프로젝션하고, **`GET /routes/{routeId}` 가 stop 마다 살아 있는
상태를 이미 돌려주므로**(§5.3 `RouteView.StopView.status`) `nextSeq`·`completed`·`failed` 는
그 응답이 싣고 있는 행들에서 나온다. 캐시를 읽으면 조회를 아끼는 것이 아니라 **같은 사실의
둘째 출처**만 생기고, 불변규칙 7 이 그 출처를 진실로 못 쓴다고 이미 정했다. 「부재는 첫
소비자가 채운다」(§11)의 거울상이다 — **소비자 없는 쓰기는 이 표에 행이 있다는 이유로 유지되면
안 된다.** 지운 것: 어댑터·포트·`RouteMutations.progressOf`·그 폴백을 보던 IT·이 행. 폴링이
필요해지면 답은 `GET /routes/{routeId}` 이고, 그것이 느리면 **그 응답을 캐시하는 것이지 다른
키를 두는 것이 아니다.**

**이 표에는 2026-09-05 하루 동안 예외가 하나 있었다.** `lock:relay:{service}` 행의 폴백 칸에는
"없다 — 발행을 멈춘다" 가 적혀 있었다(ADR-027). 폴백이 없는 이유는 맞았다 — 락 없이 진행하면
**키 단위 순서**를 잃는데 `FOR UPDATE SKIP LOCKED` 는 중복 발행만 막는다. 틀린 것은 그 락을
**여기에 둔 것**이다. 릴레이 리더 선출은 서비스 내부 조정이고 그 인스턴스들은 같은 DB 를
공유하므로, 조정자를 DB 로 옮기면 폴백을 물을 필요 자체가 없어진다(같은 날 [ADR-027 후속
정정](adr/ADR-027-outbox-relay-leader-lock.md)). **원칙에 예외를 두는 대신 예외가 필요 없는
자리로 락을 옮겼다.** 그래서 이 표의 모든 행은 다시 폴백 칸이 채워져 있다.

`lock:plan:{waveId}` 도 같은 날 사라졌다(§5.3). 이유는 반대쪽이다 — 폴백이 없어서가 아니라
**막으려던 것을 DB 제약이 이미 막고 있어서**다.

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
| PostgreSQL 다운(서비스 1개) | 해당 서비스 5xx, 레디니스 실패, **릴레이 발행 중단**(리더십 판정 불가 — 발행할 행도 못 읽으므로 같은 사건이다, ADR-027 정정) | 트래픽 차단(프로브), 소비자 재시도 후 pause | RB-02 |
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
| 기사 스캔 이벤트 | (orderIds, type) + 상태 머신 | DB |
| at-risk 발행 | 라우트 쿨다운 | Redis (소실 시 중복 허용). 이 행이 이 표에서 **유일하게 정확성이 아닌** 줄이다 — 나머지는 「두 번 일어나면 안 되는 일」을 막지만, 여기서 두 번은 *두 번 말하는 것*이다 (ADR-046) |
| 부분 재계획 | `routes.last_replanned_at` (라우트당 10분) | DB, 재계획 트랜잭션 **안에서** 비교·갱신 (§6.8, [ADR-046](adr/ADR-046-at-risk-is-an-event.md) 결정 3). 바로 윗줄과 **짝이면서 다른 줄**이다: 저쪽은 알림 수를 지키고 이쪽은 정확성을 지킨다. 「모든 Kafka 리스너」 행이 이 자리를 대신하지 못하는 이유는 두 at-risk 가 서로 다른 `eventId` 라 `processed_events` 에게는 둘 다 **처음 보는 이벤트**이기 때문이다 |

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

**게이지는 「한 일」이 아니라 「남은 여유」를 잰다 — 멈춘 게이지는 건강해 보인다.**
주기 작업을 감시하는 값을 「마지막 실행에서 처리한 수」로 두면, 그 작업이 죽은 순간 값이
마지막 성공치에서 **멈춰 있고**, 멈춰 있는 숫자는 정상과 구별되지 않는다. 같은 것을
**여유가 얼마나 남았는가**로 재면 시계가 지나는 것만으로 값이 줄어 알림에 닿는다.
`dawnline_shipment_partitions_ahead` 가 「만든 파티션 수」가 아니라 「앞으로 덮인 날 수」인 이유가
그것이고(§5.4), `dawnline_outbox_lag_seconds` 가 「발행한 건수」가 아닌 것도 같다.

같은 계열의 설계 원칙 하나 — **실패는 원인 옆에서 나야 한다.** 관측은 그 실패를 옆으로
옮기는 데 쓰는 것이 아니라 옆에 붙어 있을 때 그 사실을 미리 말하는 데 쓴다. `shipment_events` 에
DEFAULT 파티션을 두지 않는 것(§5.4), 개정 발행이 약속창 없는 행에서 소리 내어 실패하는 것,
결정적 실패를 격리해 **그 행 옆에** `failed_at` 을 남기는 것([ADR-015](adr/ADR-015-outbox-publish-side-quarantine.md))이
전부 같은 문장의 다른 자리다.

| 메트릭 | 타입 | emit 주체 | 라벨 |
|---|---|---|---|
| `dawnline_orders_placed_total` | counter | order | tier — **camp 는 없다**. 캠프는 fulfillment 가 정하므로(§5.2) 접수 시점에는 존재하지 않는다. 캠프별 유입은 `dawnline_wave_orders` 가 본다 |
| `dawnline_idempotent_replays_total` | counter | order | tier — 같은 멱등 키의 재요청으로 저장된 응답을 재생한 횟수. `orders_placed` 와 함께 보면 **클라이언트 재시도 폭주와 실제 주문 증가를 구분**할 수 있다 |
| `dawnline_rate_limit_decisions_total` | counter | order | outcome(allowed/limited/bypassed) — `bypassed` 는 Redis 장애로 판정을 건너뛴 것이다 (§7.2) |
| `dawnline_outbox_lag_seconds` | gauge | 전 서비스 | service |
| `dawnline_outbox_unpublished` | gauge | 전 서비스 | service |
| `dawnline_outbox_failed` | gauge | 전 서비스 | service — 격리된(미해결) outbox 행 수 (§4.6) |
| `dawnline_outbox_leader` | gauge | 전 서비스 | service — 릴레이 리더십([ADR-027](adr/ADR-027-outbox-relay-leader-lock.md)). **1** 리더(발행 중) · **0** 팔로워(정상, 다른 인스턴스가 리더) · **-1** 판정 불가(DB 세션 장애). 0 과 -1 을 합치지 않는 이유는 발행을 멈추는 결정은 같아도 <em>봐야 할 곳</em>이 정반대이기 때문이다. 이 값에 별도 알림을 걸지 않는다 — 결과가 `dawnline_outbox_lag_seconds` 로 곧바로 나타나고 그 알림이 §9.4 에 이미 있다. 이 게이지는 <em>왜</em> 지연이 오르는지를 말한다 |
| `dawnline_event_processed_total` | counter | 전 소비자 | consumer, eventType, outcome(ok/dup/rejected/dlq) |
| `dawnline_event_rejected_total` | counter | 전 소비자 | **consumer, eventType, reason** — 비즈니스 규칙 위반으로 무시한 이벤트 (§4.6). `outcome=rejected` 가 "몇 번" 을 세고 이쪽이 "왜" 를 센다. 예약해 둔 라벨 확장을 Phase 2-8 에서 붙였다 — 거부하는 소비자가 order·fulfillment 둘이 되어 "누가 무엇을" 이 필요해졌다. **세 라벨은 이 카운터를 올리는 모든 곳이 같이 써야 한다**(`IdempotentConsumer`·두 리스너): Prometheus 는 같은 이름의 미터가 같은 라벨 키 집합을 갖기를 요구하므로 한쪽만 붙이면 다른 쪽 등록이 실패한다 |
| `dawnline_event_stale_total` | counter | 전 소비자 | consumer, eventType — **순서 역전을 흡수하느라 무시한 이벤트**. 둘이 같은 이름을 쓴다: ① 이미 지나온 지점으로의 전이([ADR-017](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md) 축 규칙) ② 이미 적용한 개정보다 낮거나 같은 `route.assigned`(tracking, [ADR-045](adr/ADR-045-revision-comparison-is-per-route.md)) ③ **어느 라우트에도** 그 주문의 stop 이 없는 `delivery.status`(dispatch, [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) — 개정이 지운 stop 의 뒤늦은 스캔이거나 라우트가 정리된 뒤의 replay 다. **「이 라우트에 없다」가 아니다**: 재계획이 옮긴 주문의 상태는 버리지 않고 옮겨 적용하며 `dawnline_status_after_relocate_total` 로 센다. **개정 번호로 거르지 않으므로** 이 자리가 그 판정의 전부다). 셋 다 「처리하지 못했다」가 아니라 <em>설계된 동작</em>이라 `dawnline_event_rejected_total` 을 올리지 않는다 — 구별이 필요하면 `eventType` 으로 갈린다 |
| `dawnline_wave_orders` | gauge | fulfillment | camp, tier — 마감 시점의 편입 주문 수. `waves.order_count` 는 마감 전 0 이므로([ADR-025](adr/ADR-025-wave-admission-share-lock.md)) 이 값이 편입량의 유일한 관측 경로다. 스크레이프마다 집계하지 않고 **마감할 때 이미 센 값**을 남긴다 — 관측이 §8.2 피크에 부하가 되면 안 된다 |
| `dawnline_fc_fallback_total` | counter | fulfillment | camp, reason(tier/cold/inventory) — 캠프의 홈 FC 가 §5.2 1~3단계 필터에서 떨어져 대체 FC 를 고른 횟수. 계속 오르는 캠프는 홈 FC 배정이 잘못됐거나 그 FC 의 역량이 부족한 것이다 |
| `dawnline_promise_revised_total` | counter | fulfillment | camp, tier — 하류가 상류의 약속을 개정한 횟수 (§5.2, Phase 2) |
| `dawnline_geo_index_loaded` | gauge | fulfillment | index(fc/camp) — Redis GEO 적재 성공 여부 0/1. **레디니스가 아니라 이 게이지가 GEO 상태를 말한다**(§8.6, ADR-016 후속 정정). 0 이어도 서비스는 폴백으로 정상 동작한다 |
| `dawnline_geo_lookups_total` | counter | fulfillment | index, outcome(redis/bypassed) — `bypassed` 는 Redis 를 건너뛰고 DB 전체 조회 + 메모리 하버사인으로 답한 것이다(§7.2). 레이트 리밋의 `bypassed` 와 같은 어휘를 쓴다 — **폴백은 조용히 일어나면 안 된다** |
| `dawnline_plan_duration_seconds` | histogram | dispatch | strategy, mode, **termination**(converged/deadline) — **알고리즘 시간만**이다(§6.7). 영속화는 아래 짝이 잰다. `termination` 은 2026-09-24 에 붙었다: `deadline` 이면 마감에 잘려 하지 못한 일이 있고 그 결과는 그날의 기계 속도에 달린다([ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md)) — 운영에서는 **잘린 계획의 비율**이고, CI 에서는 시간 대신 보는 값이다 |
| `dawnline_plan_persist_seconds` | histogram | dispatch | camp — 라우트·stop·설명 저장과 outbox 기록에 걸린 시간. `dawnline_plan_duration_seconds` 와 **한 쌍**이고, 둘을 나눠 두는 것이 [ADR-029](adr/ADR-029-optimizer-io-is-bulk-not-orm.md) 의 요점이다 — 한 수치였을 때 30초 예산의 75% 를 ORM 이 쓰고 있는 것이 보이지 않았다. 목표 5,000건 ≤ 3초 |
| `dawnline_plan_cost_krw` | gauge | dispatch | camp |
| `dawnline_plan_unassigned` | gauge | dispatch | camp |
| `dawnline_plan_degraded_total` | counter | dispatch | camp, reason(LAG/BUDGET) — **자동 열화만** 센다([ADR-034](adr/ADR-034-degrade-mode.md)). 운영자가 `mode=FAST` 를 지정한 계획은 들어가지 않는다 — 사람이 고른 것은 시스템이 밀려서 포기한 것이 아니고, 섞으면 이 값이 「성수기에 무엇을 포기했나」가 아니라 「누가 FAST 를 몇 번 썼나」가 된다. 개별 답은 `route_plans.mode_reason` 이 든다 |
| `dawnline_plan_backlog_unknown_total` | counter | dispatch | camp — 랙을 **모른 채** 내린 자동 모드 판단. 모름은 0 이 아니다(§6.7) — 이 값이 오르는 동안 열화 판단은 조건 둘 중 하나만 보고 있고, 그 사실이 안 보이면 「랙 조건이 한 번도 발화하지 않았다」가 건강의 증거처럼 읽힌다. `dawnline_geo_lookups_total{outcome=bypassed}` 와 같은 어휘다 — **폴백은 조용히 일어나면 안 된다.** 운영자 재실행·정체 회수는 볼 파티션이 없어 정상적으로 오르므로, 0 이어야 하는 값이 아니라 **비율**을 보는 값이다 |
| `dawnline_cancel_too_late_total` | counter | dispatch | camp — 이미 `ARRIVED`/`COMPLETED` 인 stop 에 도착해 **거부한** `order.cancelled` (§6.10, [ADR-026](adr/ADR-026-dispatch-cancellation-window.md)). order-service 의 축 밖 거부 카운터와 **한 쌍**이다 — 저쪽은 "취소된 주문에 배차가 왔다", 이쪽은 "배송된 주문에 취소가 왔다" 를 세고 둘 다 같은 경합 창의 양 끝이다. 오르면 볼 곳은 dispatch 가 아니라 order-service 의 `order.dispatched` 컨슈머 랙이다 |
| `dawnline_at_risk_total` | counter | tracking | camp — `route_revisions.camp_id` 가 그 출처다(§5.4). **`shipments` 가 아니라 여기인 이유**: 캠프는 라우트의 성질이고 at-risk 판정도 라우트 단위라 결이 같다. 주문 단위 표에 두면 라우트 속성을 행 수만큼 비정규화하게 된다. 라벨 집합은 나중에 바꿀 수 없으므로(같은 이름의 미터가 라벨 키를 바꾸면 등록이 실패한다 — `dawnline_event_rejected_total` 의 같은 문단) **카운터가 처음 등록되는 Phase 5-1b 전에** 보관을 먼저 넣었다 |
| `dawnline_at_risk_cooldown_bypassed_total` | counter | tracking | 라벨 없음 — at-risk 쿨다운(Redis)을 쓰지 못해 **쿨다운 없이 발행한** 횟수 (§7.2 fail-open). 건너뛰면 Redis 장애가 곧 위험 감지 중단이 되므로 발행하는 쪽을 고르고, 그 사실을 여기서 센다. `dawnline_geo_lookups_total{outcome=bypassed}` 와 같은 어휘다 — **폴백은 조용히 일어나면 안 된다.** 이 값이 오르는 동안 「알림이 늘었다」는 위험이 늘어난 것이 아니라 Redis 가 죽은 것이다 |
| `dawnline_scan_after_cancel_total` | counter | tracking, **dispatch** | 라벨 없음 — `CANCELLED` 인 shipment 에 도착해 **무시한** 기사 스캔 (§5.4). 기사가 취소를 못 받고 배송한 것이다. dispatch 의 `dawnline_cancel_too_late_total` 과 **한 쌍**이고 둘은 같은 경합 창의 양 끝이다 — 저쪽은 「배송된 주문에 취소가 왔다」, 이쪽은 「취소된 주문이 배송됐다」. camp 라벨을 붙이지 않는 이유는 `shipments` 에 칸이 없기 때문이다 — `dawnline_at_risk_total` 이 camp 를 갖게 되는 시점에 같이 붙인다. **dispatch 도 같은 이름으로 센다**(2026-09-22, [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md)): `CANCELLED` 인 `route_stops` 행에 도착한 `delivery.status` 다. 자리는 `job` 으로 갈리고, **둘이 갈리는 것이 정보다** — 개정이 tracking 에 닿기 전에는 dispatch 쪽만 오른다. 저쪽이 「취소된 배송이 스캔됐다」이면 이쪽은 「계획에서 뺀 지점에 배송이 일어났다」이고, 뒤쪽은 다음 계획을 틀리게 할 수 있다 |
| `dawnline_status_after_relocate_total` | counter | dispatch | 라벨 없음 — 이벤트가 말한 라우트가 아니라 **다른 라우트**의 stop 에 적용한 `delivery.status` ([ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 2). 재계획이 주문을 옮기는 동안 기사가 옛 라우트에서 배송을 끝낸 것이다. **`dawnline_event_stale_total` 과 섞지 않는다** — 저쪽은 버린 것이고 이쪽은 <em>적용한</em> 것이며, 이 값이 §6.8 재계획의 **경합 창의 크기**다. 0 이 정상이 아니라 재계획이 도는 동안 조금씩 오르는 값이고, 급히 오르면 볼 곳은 dispatch 가 아니라 `delivery.status` 컨슈머 랙이다. `dawnline_cancel_too_late_total` 과 같은 종류의 수치다 — 이상이 아니라 **폭** |
| `dawnline_scan_after_relocate_total` | counter | tracking | 라벨 없음 — 기사가 찍은 `(routeId, stopSeq)` 가 **지금 tracking 이 아는 자리와 다른** 스캔 ([ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 결정 1). 단말은 개정 r 의 번호로 찍고 tracking 은 r+1 을 이미 적용한 창이다. 무시하지 않는다 — `orderIds` 로 풀어 **적용하고** 센다. dispatch 의 `dawnline_status_after_relocate_total` 과 **한 쌍**이고 같은 경합의 양 끝이다: 이쪽은 「기사가 옛 계획으로 찍었다」, 저쪽은 「옛 계획으로 찍힌 사실이 dispatch 에 닿았다」. **이쪽이 먼저 오른다** — 기사가 개정을 늦게 받는 것이 원인이면 이쪽만 오르고, dispatch 의 컨슈머 랙이 원인이면 저쪽만 오른다. 둘이 갈리는 것이 그 구별이다 |
| `dawnline_replan_total` | counter | dispatch | outcome(applied/cooldown/no-anchor/no-candidate/no-gain) — §6.8 부분 재계획이 `delivery.at-risk` 하나를 받고 **무엇을 했는가**([ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 결정 5). 다섯 갈래를 한 카운터의 라벨로 두는 이유는 **합이 곧 트리거 수**여야 하기 때문이다 — 나누면 「받았는데 아무 갈래에도 안 들어간 것」이 보이지 않는다. 실패를 DLQ 로 보내지 않으므로 이 라벨이 그 자리를 대신한다: `no-candidate`·`no-gain` 은 재시도로 달라지지 않는 <em>결과</em>이고 DLQ 는 「처리하지 못했다」의 자리다(§4.6). **`no-anchor` 가 `no-gain` 과 따로 있는 이유**는 모름이 0 이 아니기 때문이다 — 편차를 모른 채 0 으로 두면 출발 지연 라우트가 「옮겨도 이득 없음」으로 조용히 닫힌다(`PlanModeReason.LAG_UNKNOWN` 과 같은 규칙) |
| `dawnline_at_risk_deviation_mismatch_total` | counter | dispatch | 라벨 없음 — dispatch 가 자기 `route_stops.actual_at` 으로 계산한 편차와 `delivery.at-risk` 페이로드의 `deviationSeconds` 가 **60초 넘게 갈린** 횟수 ([ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 결정 2). 페이로드는 입력이 아니라 **대조값**이고, 이 값이 오른다는 것은 tracking 과 dispatch 가 같은 라우트를 다르게 보고 있다는 뜻이다 — 원인은 `delivery.status` 컨슈머 랙 · 개정이 한쪽에만 닿음 · 기사 단말의 밀린 스캔 중 하나다. 셋을 이 카운터 혼자 가르지는 못하지만 **갈린다는 사실 자체가 먼저 필요하다.** 허용 오차를 둔 이유: 두 값은 서로 다른 시각 원천에서 오므로(스캔의 `occurredAt` 과 저장 정밀도로 자른 `Clock`) 초 단위 일치를 요구하면 이 카운터는 늘 켜져 있어 아무 말도 하지 않는다 |
| `dawnline_shipment_partitions_ahead` | gauge | tracking | 라벨 없음 — 오늘을 포함해 앞으로 덮여 있는 `shipment_events` 일 파티션 수 (§5.4). 생성 스케줄러가 죽으면 날마다 1씩 줄고 **0 에서 스캔 INSERT 가 실패한다**. 마지막 성공한 실행이 남긴 최대 파티션 날짜에서 스크레이프 시점의 오늘을 뺀 값이라, 스케줄러가 멈추면 값이 그대로 멈추는 것이 아니라 줄어든다 — 멈춘 게이지는 건강해 보이기 때문이다 |
| `dawnline_delivery_on_time_ratio` | gauge | **ops-api** | camp, basis(promised/revised) — §8.1 참고. 두 값을 <em>따로</em> 낸다. 창은 「직전 24시간」이 아니라 **현재 버킷 포함 UTC 정시 버킷 24개**(`kpi_delivery_hourly` 의 24행 합 — 현재 버킷은 늘 부분이라 23시간 남짓~24시간)이고 1분마다 다시 센다. 분모는 완료 + **실패**, 취소·배차 불가는 뺀다(§5.5 「KPI — 두 축, 뷰」). 결과가 없는 캠프와 갱신 실패 중에는 `NaN` — 0 도 마지막 값도 아니다. 약속을 모르는 결과도 빠지고, 그 수는 아래 `dawnline_kpi_excluded` 가 낸다 |
| `dawnline_kpi_excluded` | gauge | **ops-api** | reason(promise_unknown) — 정시율의 창에서 **모집단 밖으로 빠진** 결과: 완료·실패했는데 약속(또는 캠프)을 아직 모른다(`kpi_delivery_hourly.outcome_without_promise` 의 합, 캠프가 없는 행 포함). 분모에서 조용히 빠지는 것은 실패를 빼서 정시율을 올리는 것과 같은 부류다 — **부재는 값이 아니지만 부재의 수는 값이다.** 정상에서는 프로젝션 랙만큼의 일시값이고 계속 0 이 아니면 `fulfillment.planned` 가 오지 않고 있다. 갱신 실패 중에는 `NaN` — 0 은 「빠진 것이 없다」는 주장이다 |
| `dawnline_ops_commands_total` | counter | **ops-api** | action(`RUN_PLAN`·`REASSIGN_STOP`·`CANCEL_ORDER`), result(`SUCCEEDED`·`REJECTED`·`FAILED`·`UNKNOWN`) — 감사 행의 결과를 **커밋한 뒤에** 센다(CLAUDE.md 「카운터는 커밋 뒤에 센다」). `PENDING` 은 세지 않는다 — 끝나지 않은 커맨드의 수는 카운터가 아니라 `audit_logs` 가 안다 |
| `dawnline_kpi_refresh_age_seconds` | gauge | **ops-api** | 라벨 없음 — 마지막으로 **성공한** KPI 갱신 뒤로 흐른 초. 스크레이프마다 계산하므로 갱신이 멈추면 값이 멈추지 않고 커진다(성공한 적이 없으면 기동부터). 위 둘은 갱신이 죽으면 `NaN` 이고 **`NaN` 에는 어떤 비교 알림도 울리지 않는다** — 그래서 알림은 이 값에 건다(§9.4). `dawnline_shipment_partitions_ahead` 와 같은 모양이다 |

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
**`auditId`** (2026-09-24, §5.5 「커맨드 위임」): ops-api 가 코어를 부를 때 싣는 `X-Dawnline-Audit-Id` 헤더를 코어의
`MdcFilter` 가 MDC 로 옮긴다. **요청 헤더를 MDC 에 넣는 유일한 자리**이고 값이 UUID 형식일 때만 받는다 — 헤더는
누구나 보낼 수 있으므로 형식이 아니면 버린다(로그 줄에 임의 문자열이 실리지 않게). 값은 ops-api 가 만든 UUIDv7
이라 개인을 식별하지 않는다.

### 9.4 대시보드·알림 (저장소에 JSON으로 커밋)

- `Order Intake`: rps, p99, 429/5xx, outbox 지연
- `Waves & Plans`: 웨이브별 주문 수, 계획 시간, 비용, 미배정, degraded
- `Delivery`: 정시율, at-risk, 실패, 라우트 진행
- `Platform`: consumer lag, DLQ 건수, DB 커넥션, JVM
- 알림 규칙: outbox 지연 > 30s, `dawnline_outbox_failed` > 0(격리 행 발생 — RB-05), DLQ 신규 > 0, consumer lag > 1,000, 계획 시간 p95 > 45s, 정시율 < 95%(창은 「직전 24시간」이 아니라 **현재 버킷 포함 UTC 정시 버킷 24개** — 현재 버킷은 늘 부분이다), **`dawnline_kpi_refresh_age_seconds` > 300**(**초기값 — Phase 7 peak-day 에서 재검토**. KPI 갱신이 5번 연속 실패했다 — 그 동안 정시율은 `NaN` 이라 바로 앞의 정시율 알림은 **울리지 않는다**. 이 알림이 없으면 `NaN` 은 정직하지만 아무도 못 듣는다, §5.5), **`dawnline_kpi_excluded{reason="promise_unknown"}` > 0 이 30분 지속**(**초기값 — Phase 7 peak-day 에서 재검토**. 결과는 났는데 약속을 모르는 주문이 정시율에서 빠지고 있다 — 프로젝션 랙으로 설명되는 길이를 넘었으면 `fulfillment.planned` 가 오지 않고 있다), **`dawnline_rate_limit_decisions_total{outcome="bypassed"}` 증가**(Redis 장애로 레이트 리밋이 꺼졌다 — 무인증 API 의 유일한 남용 방지 수단이 사라진 상태다, RB-03), **`dawnline_cancel_too_late_total` 증가**(배송이 끝난 주문에 취소가 도착했다 — 물리적 배송과 주문 상태가 어긋난 건이 생겼고 사람이 처리해야 한다. 자동 보상은 없다, §6.10), **`dawnline_shipment_partitions_ahead` < 2**(`shipment_events` 파티션 생성이 멈췄다 — 하루 뒤면 기사 스캔의 INSERT 가 `no partition ... found for row` 로 실패한다, §5.4·RB-06), **`dawnline_ops_commands_total{result="UNKNOWN"}` 증가**(운영자 커맨드가 코어에 적용됐는지 모른다 — 사람이 `auditId` 로 코어 로그를 보고 닫는다, RB-07)

### 9.5 런북 (`docs/runbooks/RB-0x.md`)

RB-01 Kafka 복구 · RB-02 DB 장애 · RB-03 Redis 복구 · RB-04 계획 정체/강제 재실행 · RB-05 DLQ 재처리·outbox 격리 재큐(§4.6) · RB-06 피크 대비 체크리스트(파티션·인스턴스·룰 파라미터 사전 점검) · RB-07 감사 `UNKNOWN`·오래된 `PENDING` 해소(§5.5 — 코어 로그·트레이스에서 그 행의 `auditId` 를 찾아 적용 흔적이 있으면 `SUCCEEDED`, 요청이 닿은 흔적이 없으면 `FAILED` 로 사람이 닫는다. 흔적으로도 못 가리면 코어의 현재 상태(웨이브·라우트·주문)를 보고 닫고, 무엇을 근거로 닫았는지 남긴다. 이 일을 코드로 옮기는 것 — ops-api 가 코어 상태를 다시 읽어 닫기 — 은 `UNKNOWN` 이 실제로 쌓이면 연다, [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md) 재검토 지점 4).

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
  (2026-09-24) 발급은 `make token ROLE=…`(만료 12시간), ops-api 는 서명·만료·역할만 본다. 사용자 관리는 범위
  밖이다 — 로그인 엔드포인트가 없다(§5.5, [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md) 결정 2).
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
| 문서 | springdoc-openapi | **3.1.1** (Boot 4 라인) — Phase 1 에 3.1.0 으로 동작 확인, 2026-09-23 에 3.1.1 (보안 권고 8건) | OpenAPI 3.1 자동 생성, `contracts/openapi/<service>.yaml` 로 내보내고 `OpenApiContractIT` 가 코드와의 일치를 검사. **REST 표면이 있는 서비스마다 생성물과 계약 IT 를 둔다** — 목록이 아니라 조건이다(2026-09-19 정정). 열거였을 때 그 목록은 `order-service`(Phase 1)·`tracking-service`(Phase 5-1a) 둘이었고, springdoc 이 붙어 있는데 생성물이 없는 `dispatch-service` 는 그 문장 **밖**에 있었다 — 조건으로 적으면 새 REST 표면이 스스로 대상이 된다. tracking 쪽은 사람만 읽는 것이 아니라 **`sim-runner` 가 다른 모듈에서 그 엔드포인트를 부르므로**(§5.6) 두 모듈이 공유하는 유일한 계약이다. 계약 IT 는 **오류 본문(`ProblemDetail`)과 성공 본문(이름 있는 타입)을 둘 다** 본다 — 한쪽만 보면 「오류를 파싱할 수 있는가」까지만 답한다. dispatch 의 생성물은 **Phase 6-0 에서 만들었다**(2026-09-23). 부재를 그때까지 둔 것은 *문서가 거짓을 말하는* 상태가 아니라 문서가 **없는** 상태였기 때문이고, 부재는 첫 소비자가 나타나는 시점에 채우는 것이 소비자 주도 원칙과 맞는다 — 그 소비자가 ops-api 다. **채우면서 결함 하나가 나왔다**: `PUT /rules/{ruleId}` 는 `Map<String, Integer>` 를, `POST /vehicles`·`POST /drivers` 는 `Map<String, UUID>` 를 돌려주어 문서가 성공 본문을 `type: object` 로 적고 있었다 — order-service 의 `ResponseEntity<Object>` 와 **같은 부류**이고, 그것을 잡은 것이 열거가 아닌 조건으로 적힌 `successBodiesWithoutNamedType()` 이다(되돌려 확인: `PUT /api/v1/rules/{ruleId} → 200 (이름 없는 object)`). 이름 있는 record 로 바꿨고 직렬화 결과는 같다. **`ProblemDetail` 의 확장 칸은 최상위다** (2026-09-24, [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md)): springdoc 은 확장 멤버 맵을 `properties` 라는 중첩 객체로 그렸고 실제 본문은 최상위로 펼친다 — 그 문서로 만든 ops-api 의 위임 클라이언트가 `code` 를 잃는 상태였다. **발행된 문서가 틀린 경우라 아는 순간 고쳤다**(부재와 다르다). 생성기의 오류는 생성기 쪽에서 — `libs/web` 의 `ProblemDetailSchema`(springdoc 을 쓰는 서비스 전부에 자동 구성)가 평탄화하고, 세 서비스의 `OpenApiContractIT` 가 본문 쪽 사실(`ScanApiIT` 의 `jsonPath("$.code")`)을 문서 쪽에서 대조한다 |
| 위임 클라이언트 생성 | openapi-generator (`spring` · `spring-http-interface`) | **7.25.0** — 2026-09-24 채택([ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md)) | ops-api 의 코어 위임 클라이언트를 **커밋된 `contracts/openapi/*.yaml` 에서 빌드 때** 만든다. 채택 기준(표준 템플릿·문서화된 옵션만·그대로 컴파일·Jackson 3 왕복·새 런타임 의존 없음)을 시도 전에 적었고 다섯 다 참이다. 생성물은 커밋하지 않는다. HTTP 계층은 Boot 4 의 HTTP Service Client(`@ImportHttpServices`) |
| 회복탄력성 | Resilience4j | **아직 쓰지 않는다.** `resilience4j-spring-boot4:2.4.0` 은 해결되지만 `resilience4j-spring6`(Spring Framework 6)을 끌고 온다 | Phase 3 의 OSRM 어댑터(Retry·CircuitBreaker)와 Phase 7 의 전역 `Bulkhead`(§8.3)에서 다시 판단한다. Phase 1 의 Redis 장애 차단기는 도입하지 않았다 — CircuitBreaker 가 자기 시계로 돌아 창 만료를 테스트하려면 실제로 기다려야 하고(불변규칙 12), 필요한 것은 `AtomicLong` 하나였다 |
| 관측성 | Micrometer + OpenTelemetry, Prometheus, Grafana, Tempo | 최신 안정 이미지 | Boot 4.1의 OTel 개선 활용 |
| 테스트 | JUnit(Boot BOM), Testcontainers, ArchUnit, WireMock(OSRM 스텁), k6 | 최신 안정 | §13 |
| 최적화(선택) | Timefold Solver Community | — | **도입하지 않는다** ([ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md), 2026-09-18). 비교 대신 §6.9 의 **고정비 하한 열**. Phase 7-6 에 여유가 있으면 `medium` 한 개 한정 |
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
│   ├── observability/   # 메트릭 명명, MDC 필터, 로그 설정
│   └── web/             # RFC 9457 오류 응답의 모양(ProblemDetailsAdviceSupport) — ADR-049
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

`libs/*` 안에서도 경계가 하나 더 있다: **`common` 은 프레임워크를 모르고, 나머지 셋은 Spring 을 안다**([ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md)). `common` 의 `main` 에 Spring 이 들어가면 `tools/benchmark`(Spring 없이 도는 CLI)가 그것을 끌고 오게 되고, 불변규칙 5 의 근거가 도메인 패키지 밖에서 무너진다. 그 경계는 문장이 아니라 **ArchUnit 규칙 10** 이 지킨다 — build 파일의 주석은 의존을 한 줄 더해도 그대로 있다. Spring 을 아는 공유 코드의 자리는 `libs/web` 이다.

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

**ArchUnit 규칙 목록**: (1) `domain`은 `org.springframework`, `jakarta.persistence` 의존 금지 (2) `application`은 `adapter` 의존 금지 (3) `com.dawnline.<svc>`는 다른 `<svc>` 패키지 참조 금지 (4) Kafka 리스너 클래스는 `adapter.in.messaging`에만 존재 (5) `@Transactional`은 `application` 계층에만 (6) `domain`·`application`은 `org.springframework.kafka` 의존 금지 — 발행은 Outbox 를 거친다(불변규칙 1) (7) 서비스 코드는 시스템 시계를 직접 읽지 않는다 — `Instant.now()`·`Clock.systemUTC()`·`Clock.systemDefaultZone()`·`now(ZoneId)`·`System.currentTimeMillis()` 금지(불변규칙 12) (8) `adapter.in.web` 의 매핑 경로에 리터럴 API 버전(`/api/v1/...`) 금지 — 버전은 `{version}` 자리표시자와 `ApiVersionConfigurer` 로 해석한다([ADR-009](adr/ADR-009-url-path-api-versioning.md) 결정 2) (9) `@ControllerAdvice` 계열 클래스는 `libs/web` 의 `ProblemDetailsAdviceSupport` 를 상속한다 — 오류 응답의 모양은 한 곳에서 정해진다([ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md) 결정 4) (10) `libs/common` 의 **main** 은 `org.springframework`·`jakarta.persistence` 의존 금지 — Spring 을 아는 공유 코드는 `libs/web` 에 산다(ADR-049 결정 2).

규칙 8 은 불변규칙이 아니라 **ADR 을 강제한다**. 그것이 이 규칙이 생긴 이유이기도 하다 — ADR-009 는 order-service 의 첫 컨트롤러와 함께 쓰였고 그 서비스는 지켰지만, dispatch 의 컨트롤러 셋은 리터럴 `/api/v1` 로 들어왔다(2026-09-19). 결정이 **한 서비스에만 적용되고 있다**는 사실을 아무 검사도 보고 있지 않았고, 「운영자 API 라서 다르다」는 ADR 에 없는 예외였다. ADR 은 결정을 적지만 그 결정이 다음 서비스에서도 지켜지는지는 말해 주지 않는다.

규칙 9 와 10 은 **[ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md) 를 강제한다** — 규칙 8 과 같은 부류다. 둘 다 표본이 사는 자리가 다른 규칙들과 다르다. 규칙 9 의 양성 표본은 `ProblemDetailsAdviceSupport` 를 상속해야 하는데 그 타입은 `libs/web` 에 살고 `libs/common` → `libs/web` 의존은 **방향이 반대**다. 그래서 이 규칙의 표본만 `libs/web` 의 테스트 소스셋에 둔다(`ProblemDetailsAdviceRuleTest`). 같은 이유로 규칙이 기반 클래스를 **문자열 FQN** 으로 가리키는데, 문자열 링크는 **끊어져도 조용하다** — 이름이 바뀌면 규칙은 아무것도 매치하지 않으면서 통과한다. 그 자리를 아래 규칙 3(대조 검사)이 막는다: 같은 테스트가 `HexagonalArchitectureRules.ERROR_ADVICE_BASE` 와 `ProblemDetailsAdviceSupport.class.getName()` 을 맞춰 본다. 규칙 10 은 대상이 서비스가 아니라 `libs/common` 이라 `allRulesFor(service)` 에 들어가지 않고 그 모듈의 테스트가 직접 건다(`LibsCommonIsFrameworkFreeTest`); **분석 대상을 main 출력 경로로 좁히는 것이 전제**이고(이 모듈의 테스트 클래스패스에는 Spring 을 일부러 참조하는 위반 표본이 있다) 그 전제를 첫 어설션이 스스로 말한다 — 0 개를 읽으면 규칙은 검사 없이 통과한다.

규칙 7이 이름이 아니라 **인자 타입**으로 판정하는 이유: `LocalTime.now(Clock)` 은 주입받은 시계를 읽는 <em>올바른</em> 형태이고 `LocalTime.now(ZoneId)` 는 시스템 시계를 읽는 위반이다. 이름만 보면 둘이 같아 보인다 — 규칙을 처음 켰을 때 `TierEligibility.nowInServiceZone()` 이 그렇게 잘못 걸렸다. 분석 대상에서 테스트 클래스는 뺀다(`DoNotIncludeTests`): 규칙은 프로덕션 구조를 서술하는 것이고, "생성자가 잘못된 인자를 거부하는가" 를 보는 테스트는 버릴 객체를 만들려고 시스템 시계를 부를 수 있다.

규칙 6이 따로 필요한 이유: `libs/messaging` 이 Kafka 의존을 `api` 로 노출하므로 `KafkaTemplate` 이 5개 서비스 전부의 컴파일 클래스패스에 있다. 유스케이스가 그것을 직접 부르면 도메인 변경과 이벤트 발행이 서로 다른 트랜잭션이 되는데, 규칙 5는 어노테이션의 *위치*만 보므로 이를 잡지 못한다.

**규칙의 검증 상태**: 열 규칙 <strong>전부</strong> 위반 표본으로 "잡아야 할 것을 잡는지"까지 확인된다 — 여덟은 `libs/common` 의 `archunit/samples/bad` 에, 규칙 9 의 표본만 `libs/web` 의 테스트 소스셋에 있다(위 문단의 방향 문제). 규칙 10 의 음성 방향은 표본이 아니라 **좁히기를 뺀 결과**로 본다 — 그 모듈의 테스트 소스셋 자체가 Spring 을 참조하는 표본 집합이기 때문이다. Phase 0 마감 시점에는 규칙 3·4·5가 대상 0개라 미검증이었고, Phase 1에서 첫 `@Transactional`(규칙 5)·첫 `@KafkaListener`(규칙 4)·서비스 간 참조 표본(규칙 3)이 생기며 채워졌다.

규칙별 주의점 세 가지. (1) 규칙 1의 표본은 금지 대상 중 Spring 쪽만 건드린다 — `libs/common` 의 test 클래스패스에 `jakarta.persistence` 가 없기 때문이며, JPA 는 같은 `resideInAnyPackage` 술어에 들어가는 다른 패키지 문자열일 뿐 검사 경로가 다르지 않다. (2) 규칙 3의 표본은 `that` 절이 서비스 패키지로 좁혀져 있어 `com.dawnline.order`·`com.dawnline.fulfillment` 패키지에 두어야 한다. 그 클래스들은 `libs/common` 의 테스트 소스에만 있고 서비스의 테스트 클래스패스에는 없으므로 실제 분석에 섞이지 않는다. (3) 규칙 3·7은 <strong>반대 방향</strong>(통과해야 할 표본이 통과하는지)도 함께 본다 — 그 방향이 없으면 "모든 참조를 막는" 규칙이나 "시각을 아예 못 읽게 만드는" 규칙이 되어도 테스트가 통과한다.

**불변 규칙 ↔ 강제 수단 매핑** (CLAUDE.md 「아키텍처 불변 규칙」 13개 기준). ArchUnit이 닿는 것은 13개 중 6개(1·2·3·4·5·12)이고 그중 온전히 강제되는 것은 5·12번이다. 나머지는 API 설계·DB 권한·컴파일러·CI·리뷰가 맡는다 — 이 표는 "무엇이 자동으로 막히지 *않는지*"를 보이는 것이 목적이다.

| # | 불변 규칙 | ArchUnit | 그 밖의 강제 수단 | 음성 검증 |
|---|---|---|---|---|
| 1 | Outbox 필수 | 규칙 6(직접 발행 차단), 규칙 5(트랜잭션 경계 위치) | `OutboxAppender` 가 유일한 발행 API — `libs/messaging` 은 다른 발행 경로를 제공하지 않는다. 어노테이션이 <em>사라지는</em> 것은 ArchUnit이 못 잡으므로 `PlaceOrderTransactionTest` 가 그 존재를 직접 확인한다 | 규칙 6 ✅ / 규칙 5 ✅ |
| 2 | 멱등 소비자 필수 | 규칙 4 — 리스너의 *위치*만 제한. 멱등 체크를 했는지는 보지 못한다 | `IdempotentConsumer` API, PR 체크리스트, 리스너 IT 가 같은 이벤트를 두 번 보내 상태가 한 번만 바뀌는지 확인. **예외 하나**: `tools/sim-runner` 의 기사 시뮬레이터는 `route.assigned` 를 구독하지만 DB 도 `processed_events` 도 없고, 대신 `DriverFleet` 이 라우트별 개정 최댓값으로 거른다(tracking 의 `route_revisions` 와 같은 모양, `DriverFleetTest`). **예외가 성립하는 이유는 「도구라서」가 아니라 「하류가 멱등이라서」다** — 중복 소비가 만드는 것은 tracking 으로 가는 중복 스캔이고 그것은 §8.5 의 「`(orderIds, type)` + 상태 머신」이 `STALE` 로 흡수한다. **하류가 멱등이 아닌 소비자는 이 예외를 쓸 수 없다**. 그 리스너는 ArchUnit 규칙 4 의 대상도 아니다 — `tools/sim-runner` 는 `dawnline.spring-service` 규약을 쓰지 않아 ArchUnit 이 돌지 않고, 도구에는 `adapter.in.messaging` 이라는 자리 자체가 없다 (2026-09-19, Phase 5-2) | 규칙 4 ✅ / 멱등 체크 자체는 ✗ |
| 3 | 서비스 간 DB 접근 금지 | 규칙 3 — 소스 레벨 패키지 참조만 | DB 권한(`deploy/compose/initdb`): 서비스 DB·부트스트랩 DB 모두 `REVOKE CONNECT … FROM PUBLIC` | 규칙 3 ✅(양방향) / DB 권한 ✅(컨테이너에서 거부 확인) |
| 4 | 코어 서비스 간 동기 호출 금지 | 규칙 3이 부분 커버 — 모노레포 안의 패키지 참조만 잡는다. HTTP 클라이언트로 부르는 것은 못 잡는다 | PR 체크리스트, Compose 네트워크 구성 | 규칙 3 ✅ / HTTP 경로는 ✗ |
| 5 | domain 프레임워크 비의존 | 규칙 1 — 유일하게 온전히 강제된다. **규칙 10** 이 같은 근거를 `libs/common` 의 main 으로 넓힌다([ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md) 결정 2) — 그 모듈의 build 파일이 「순수 Java 다」라고 적고 있었지만 그것은 문장이지 강제가 아니었다 | — | ✅ (규칙 1 · 규칙 10 둘 다) |
| 6 | 상태 전이는 상태 머신 메서드로만 | — | 애그리거트에 세터를 두지 않는다, 코드 리뷰, **왕복 매핑 단위 테스트**. **애그리거트가 없는 자리 하나**: dispatch 의 `route_stops.status`(2026-09-22, [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) 기각 (5)). 라우트는 120 stop 까지 가고 `RouteMutations` 는 「애그리거트를 되살리지 않는다」를 명시한 포트라, 전이 규칙은 도메인의 **순수 함수**(`RouteStopTransition`)에 두고 어댑터가 판정만 받아 한 행을 쓴다. 규칙이 한 곳에 있다는 목적은 지켜지지만 **세터를 막는 장치가 없다** — 지키는 것은 `RouteStopTransitionTest` 와 리뷰다. **둘째가 묶음 B 에 온다**: ops-api 의 상태 칸 넷(`rm_orders.order_status`·`delivery_outcome`·`rm_waves.status`·`rm_routes.status`, §5.5)은 애그리거트가 아니라 **프로젝션**이라 역시 세터를 막을 자리가 없고, 앞의 넷과 달리 **행 하나에 여러 토픽이 쓰므로** 「이 전이를 받는가」 앞에 「그 행이 아직 있기는 한가」가 하나 더 있다([ADR-051](adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) — 축 규칙의 다섯 번째 자리). 지키는 것은 「순서를 뒤섞는 IT」다 — **들어왔다**(2026-09-24): 판정은 `ops.domain.Progress` 의 순수 함수(최댓값)이고 `ProgressTest` 가 네 축의 모든 쌍에서 교환법칙을 본다. `ProjectionShuffleIT`(실제 PostgreSQL, 씨 25회)·`ProjectionShuffleTest`(메모리, 씨 300회)가 같은 시나리오를 뒤섞고, 토픽·표·칸을 전부 **빼는 방식**으로 정한다. 「부재는 값이 아니다」는 타입이 지킨다 — `Patch` 가 `null` 을 받지 않는다 | 부분 — `FulfillmentOrderEntityTest`·`WaveEntityTest` 가 도메인→행→도메인 왕복에서 필드가 사라지지 않는지 본다. `RouteStopTransitionTest` 가 위 예외의 표 전체(도착 상태 × 현재 상태)를 **빼는 방식**으로 돈다 |
| 7 | Redis는 진실 저장소가 아님 | — | §7.2 폴백 표(**예외 없음** — 2026-09-05 에 하루 있었고 [ADR-027 후속 정정](adr/ADR-027-outbox-relay-leader-lock.md)이 그 행을 없앴다), 카오스 시나리오(현재 `make chaos-kafka`), 어댑터가 `DataAccessException` 을 밖으로 내지 않는다 | ✅(멱등·GEO·권역) — `PlaceOrderIT`(order)와 `GeoFallbackIT`(fulfillment)가 죽은 Redis 주소로 컨텍스트를 띄워 각각 멱등과 FC 선택이 DB만으로 성립함을 보인다. **`GeoEquivalenceIT` 는 한 걸음 더 간다** — 폴백이 *동작하는가*가 아니라 시드 전체(캠프 10 × FC 3 × 티어 3 × 냉장 2)에서 Redis 와 **같은 답**을 내는가를 본다 |
| 8 | 이벤트 계약 우선 | — | 계약 테스트(`EventContractsTest` — 스키마·예시 양방향), `contracts/events/README` §3 | ✅ |
| 9 | 돈은 정수 KRW·좌표 `NUMERIC(9,6)`·시간 `TIMESTAMPTZ` | — | 컴파일러 — `Money` 는 `long` 을 감싸는 값 객체라 부동소수 금액이 타입에서 막힌다 | ✅(타입) |
| 10 | ID는 UUIDv7 | — | `Ids.newId()`, `IdsTest`(RFC 9562 비트 레이아웃·단조 증가) | ✅(생성기) |
| 11 | 인덱스 추가 금지(설계서 명시분 외) | — | PR 체크리스트(EXPLAIN 첨부), 마이그레이션 리뷰, `docs/benchmarks/` 의 측정 기록(**넣지 않기로 한 판단도**) | 부분 — 계획을 <em>지키는</em> 테스트는 둘이다: `DispatchPersistenceIT`(`ix_cand_wave`)와 `RouteStopOrdersIndexIT`(`ix_rso_order`). 둘 다 **「통계가 있다」를 첫 어설션으로** 말한다 — 없으면 플래너가 짐작하고 그 계획은 아무것도 증명하지 않는다(2026-09-07 에 그 형태로 한 번 속았다). 나머지 인덱스는 PR 기록뿐이다 |
| 12 | 시간·난수는 주입 | 규칙 7 — 시계 쪽은 온전히 강제된다. 난수(`RandomGenerator`)는 아직 아니다 | 생성자 시그니처, seed 재현성 테스트, `libs/messaging` 이 저장 정밀도로 자른 `Clock` 빈을 제공. **규칙이 강제하는 것은 「주입받는다」이지 「테스트가 그 이음매를 쓴다」가 아니다** — `RateLimitApiIT` 는 주입된 시계를 쓰는 레이트 리밋을 **실제 시계로** 돌려, 러너가 느린 날에만 429 가 201 이 되는 형태로 2026-09-23 CI 에서 깨졌다(토큰 리필이 흘러간 벽시계만큼 일어난다). 고정 시계 빈을 넣어 닫았고, 같은 클래스의 `리필은_실제_시간이_흘러도_일어나지_않는다` 가 **벽시계를 1.2초 흘려 보내며** 그것을 지킨다 | 규칙 7 ✅(양방향) / 「이음매를 쓰는가」는 ✗ — IT 마다 사람이 본다 |
| 13 | 머지된 마이그레이션 불변 | — | CI 「마이그레이션 불변 검사」 job — PR 에서 기존 `V*.sql` 이 수정·삭제·이동되면 실패 | ✅(CI) |

**ArchUnit 규칙이 전부 불변규칙에서 오는 것은 아니다.** 위 표는 왼쪽이 불변규칙이라 규칙 2·8 이
들어갈 칸이 없다 — 그 둘이 강제하는 것은 설계 문서와 ADR 이다. 출처를 적어 두는 이유는 **규칙을
지울 때 무엇이 풀리는지 보이게** 하기 위해서다. 불변규칙을 강제하는 규칙을 지우면 `CLAUDE.md` 의
한 줄이 풀리고, ADR 을 강제하는 규칙을 지우면 **그 결정이 다음 서비스에서도 지켜지는지 아무도 묻지
않게 된다** — 규칙 8 이 생긴 이유가 정확히 그것이다.

| ArchUnit 규칙 | 출처 | 지우면 무엇이 풀리나 |
|---|---|---|
| 1 · 3 · 6 · 7 | 불변규칙 5 · 3·4 · 1 · 12 (위 표) | 그 불변규칙의 유일한 자동 강제 수단 |
| 4 · 5 | DESIGN §3.4 의 레이어 책임 (+ 규칙 5 는 불변규칙 1 을 함께 받친다) | 어노테이션의 *위치* 규약 |
| 2 | DESIGN §3.4 의 의존 방향, [ADR-007](adr/ADR-007-hexagonal-architecture-archunit.md) | 헥사고날의 방향 자체 — 불변규칙 목록에는 없다 |
| 8 | [ADR-009](adr/ADR-009-url-path-api-versioning.md) 결정 2 | 결정이 **한 서비스에만** 적용되고 있는지를 보는 유일한 자리 |

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

**꺼 둔 검증은 실패하지 않는다** — 그래서 초록으로 보인다. 위 규칙이 *전제가 조용히 무너진*
테스트를 말한다면 이쪽은 한 걸음 더 나간 형태다: 검증이 **아예 돌지 않는데도** 파이프라인이
초록이다. 없는 job 은 실패하지 않고, 실패하지 않는 것은 눈에 띄지 않는다. 2026-09-05 하루에
두 건이 나왔다.

| 무엇이 꺼져 있었나 | 어떻게 드러났나 | 교훈 |
|---|---|---|
| 벤치마크 회귀 게이트가 `if: false` 였고, 인자의 전략 이름(`sweep-greedy-nn+ls`)은 **존재하지 않았다** | Phase 3 마감 대조표가 "게이트를 켠다" 항목을 커밋과 짝지으려다 | **켜져 있다고 믿는 게이트는 없는 게이트보다 나쁘다.** 없는 게이트는 아무도 신뢰하지 않는다 |
| Compose 스모크 job 이 `needs: [build, image]` 였는데 `image` 는 **다른 러너**에서 빌드한다 — 켰다면 `check-images` 에서 죽었다 | 같은 대조표가 그 job 을 실제로 켜 보고 | **꺼 둔 job 의 결함은 켜기 전까지 보이지 않는다.** 꺼 두는 동안 그 job 은 검증이 아니라 <em>검증이 있다는 인상</em>이다 |
| `DatasetFeasibilityTest` 가 검사 대상을 `{"SMALL","MEDIUM","LARGE"}` 로 **열거**했다 — `peak` 은 그 목록에 없었다 (2026-09-12) | 병렬화 게이트를 재려고 `peak` 을 돌렸더니 총비용의 88%가 미배정 페널티였다. stop 8,411 개가 슬롯 7,200 개를 넘고 있었다 | **목록에 없는 것은 검사되지 않는데, 목록에 없다는 사실은 아무도 보지 않는다** |
| `docs/adr/README.md` 의 목록에 **039–044 여섯 줄이 없었다** — 파일도 §16 표도 있었다 (2026-09-19) | ADR-045 를 넣으려고 번호를 세다가. 그 표는 스스로 「§16 과 같은 내용」이라고 적어 두므로, 빈 줄은 「아직 없는 ADR」로 읽힌다 | **서로를 비추는 목록은 검사가 없으면 갈라진다.** 계약·시드·OpenAPI·이미지 태그에는 대조가 있었고 ADR 번호 셋만 그 밖이었다 |

앞의 셋은 "지금은 못 켠다·이건 특별하다" 는 합리적인 이유로 빠졌고, 그 이유가 사라졌는지 확인할
사람이 없었다. **넷째는 모양이 다르다** — 빼기로 한 사람이 아예 없었다. 그냥 검사가 없는
목록이었고, 그래서 여섯 줄이 조용히 비어 있었다. 그래서 규칙 셋.

1. **`if: false` 로 두는 job 에는 켜는 조건을 주석으로 적고, 그 조건이 사라지는 Phase 의 마감
   대조표에 항목으로 넣는다.** 대조표가 앞의 둘을 잡은 것은 우연이 아니라 대조표가 *코드가 아니라
   계획서*를 기준으로 읽기 때문이다.
2. **집합을 도는 검사는 열거하지 않고 전체에서 뺀다** (2026-09-12). 데이터셋·시드·job 처럼
   *구성원이 늘어나는* 집합에 거는 검사는 `@EnumSource(mode = EXCLUDE, names = …)` 처럼
   **빼는 방식**으로 적는다. 드는 방식은 새 구성원이 조용히 검사 밖에 남고, 그 사실이 어디에도
   나타나지 않는다 — 빼는 방식은 제외를 *쓰게 만들고*, 쓰인 제외는 읽힌다.
   그리고 **제외한 것이 왜 제외인지를 검사하는 테스트를 함께 둔다**
   (`overload_는_stop_기준을_일부러_어긴다`) — 그것이 없으면 제외가 「검토했는데 제외」인지
   「잊었는지」를 다음 사람이 구별할 수 없다.
3. **서로를 비추는 목록에는 대조 검사를 둔다** (2026-09-19). 「A 와 B 는 같은 내용이다」라고
   *적어 둔* 두 곳은, 그 문장이 아직 참인지 아무도 묻지 않으면 갈라진다 — 그리고 갈라진 쪽은
   빈자리라서 눈에 띄지 않는다. 이 저장소에는 이미 넷이 있었다: 이벤트 스키마 ↔ 예시
   (`EventContractsTest`), 권역 시드 ↔ 지오코더 출력(`ZoneSeedContractTest`), OpenAPI 문서 ↔
   실제 매핑(`OpenApiContractIT`), Compose `.env` 의 버전 태그 ↔ 로컬 이미지(`make check-images`).
   **ADR 번호 셋**(`docs/adr/ADR-*.md` 파일 · §16 표 · `docs/adr/README.md` 표)만 그 밖에 있었고,
   `AdrIndexConsistencyTest` 가 그 자리를 채운다. 그 검사도 규칙 2 대로 **빼는 방식**이다 —
   번호를 열거하지 않고 파일에서 전부 읽은 뒤, **표에만 있고 파일이 없는** 번호는 「문서 열이
   `—` 인가」로 스스로를 설명하게 한다(005·008·010·011·012 — 결정 방향만 정해 둔 항목들).

   **대조가 있어도 보는 자리가 좁으면 같은 일이 난다** (2026-09-19, 같은 날 둘째 건). OpenAPI 쪽
   대조는 넷 중 하나로 이미 있었는데, 검사가 문자열 포함이라 「404 가 문서에 있는가」까지만 답했다.
   *그 404 의 본문이 무엇인가*는 묻지 않았고, `contracts/openapi/order-service.yaml` 은 오류 응답
   아홉 자리를 `OrderView`·`object` 로 말하고 있었다 — springdoc 은 `@ApiResponse` 에 `content` 가
   없으면 **메서드 반환 타입**을 모든 응답에 붙인다. 문서를 보고 만든 클라이언트는 오류를 파싱하지
   못한다. 고친 검사는 문서를 구조로 읽고(`OpenApiResponses`), 상태 코드를 **열거하지 않는다** —
   2xx 가 아닌 전부가 대상이고, 2xx 를 제외한 이유는 「성공 응답에 Problem Details 가 실리지
   않는다」가 따로 말한다(규칙 2). `contains("ProblemDetail")` 은 **한 자리만 맞아도 통과한다**는
   것이 이 건의 교훈이다.

   **그리고 오류만 보는 검사는 그 자체로 좁다.** 같은 부류가 성공 쪽에 있었다 —
   `POST /api/v1/orders` 가 `ResponseEntity<Object>` 라서 201·200 의 본문이 `type: object` 로
   적혀 있었고, 그것은 「본문이 있다」와 「그 타입은 말하지 않는다」를 동시에 말한다. 오류 검사는
   2xx 를 제외하므로 이것을 **구조상 볼 수 없다.** 그래서 짝을 둔다: **4xx·5xx 는
   `ProblemDetail` 이고 2xx 는 이름 있는 타입이다**(`successBodiesWithoutNamedType`). 둘을 합쳐야
   검사가 「오류를 파싱할 수 있는가」가 아니라 **「계약이 본문을 말하는가」**를 본다.

   **좁은 자리는 클래스패스에도 있다** (2026-09-22, Phase 5-2). `sim-runner` 는 DB 가 없어
   `libs/messaging` 에서 JPA 를 빼 오는데, 그 제외를 `implementation(project(…))` <em>선언 하나</em>에
   걸어 두었다. 뒤에 추가한 `integrationTestImplementation(testFixtures(project(…)))` 가 같은
   프로젝트를 다시 선언하면서 JPA 가 돌아왔다 — Gradle 의 `exclude` 는 **선언마다** 걸린다.
   그 누수를 지켜야 할 `MessagingDependencyTest` 는 **`test` 클래스패스만** 보고 있었고, 새는 자리는
   `integrationTest` 였다. 그래서 단위 테스트는 초록이었고, 드러난 것은 `SimDriverIT` 이 컨텍스트를
   띄우다 `Failed to determine a suitable driver class` 로 죽었을 때다. 위의 OpenAPI 건과 같은
   모양이다 — **대조는 있었는데 보는 자리가 대상보다 좁았다.** 고친 검사는 같은 어설션을 두
   클래스패스에서 돌린다(`MessagingDependencyTest` · `MessagingDependencyIT`, 어설션은 한 벌).

   **그리고 같은 사실을 만드는 길이 둘이면, 하나가 다른 하나의 결손을 덮는다** (2026-09-23,
   Phase 5-3). `route.revised` 페이로드를 만드는 길은 둘이었다 — §5.3 운영자 재배정은 도메인
   객체(`PlannedRoute`)에서 만들고, §6.8 재계획은 **DB 의 `route_stops` 를 다시 읽어** 만든다.
   그 둘이 서로를 비추는 관계인데 대조가 없었고, `moveOrder` 가 만드는 새 stop 행은
   `promised_start/end` 를 넣지 않고 있었다. **도메인 경로는 그 결손을 볼 수 없다** — 약속창을
   메모리의 객체에서 가져오기 때문이다. 결손은 *DB 를 읽는 발행 경로가 생겨서야* 터졌고
   (`ReplanIT` → `약속창 없이 개정을 발행할 수 없습니다 (V6 이전 행)`), 그때까지 §5.3 의 IT 는
   전부 초록이었다. 위의 OpenAPI·클래스패스 건이 「대조는 있었는데 보는 자리가 좁았다」라면
   이쪽은 **대조할 짝이 아직 안 태어난 동안 한쪽이 옳아 보인 것**이다. 닫은 방식은 같다 —
   재배정 IT 가 「새 stop 이 생겼다」를 전제로 말하고 `promised_start/end` 가 `NULL` 인 행이
   **0 인지**를 확인한다(`DispatchAdminIT`, 빼는 방식). 이 축이 다음에 나올 자리는 「저장된 행에서
   다시 만드는」 경로가 새로 생기는 곳이다.

   그리고 제외 자체는 **모듈 전체에 한 번** 선언한다(`configurations.configureEach`).
   「선언이 늘 때마다 같은 한 줄을 기억한다」는 규칙은 조용히 샌다 — 기억에 기대는 규칙을
   기계가 지키는 규칙으로 바꾸는 것은 규칙 2 의 「빼는 방식」, CI 의 「마이그레이션 불변 검사」와
   같은 계열이다. 셋 다 사람이 잊어도 같은 답이 나오게 만든다.

**픽스처가 정하지 않은 축** — 「통과했지만 아무것도 검사하지 않는 테스트」의 목록이다. 공통점은
하나다: **지금 깨지지 않는 이유가 테스트에 적혀 있지 않다.** Phase 3 마감에서 셋이었던 것이
Phase 4-0 하나의 PR 에서 여섯이 됐고, 여섯 다 *다른 것이 우연히 그 자리를 메우고 있었다.*
Phase 4 마감에 일곱째(검사 대상 집합)가, **Phase 5-0 에 여덟째(실행 순서)**, **Phase 5-1a 에 아홉째(환경이 결함을 가린다)** 가 붙었다.

| # | 축 | 무엇이 우연이었나 | 어떻게 드러났나 |
|---|---|---|---|
| 1 | **시드 행** | `DispatchAdminIT` 가 <em>전역</em> 시드 룰(`camp_id IS NULL`)을 고치고 `@AfterEach` 로 되돌렸다 — 병렬 실행이 들어오면 무너지는 격리 | 안 깨진 채로 **닫았다** (2026-09-18, Phase 5-0): 되돌리는 대신 **캠프 범위 픽스처 행**을 만들어 고치고 지운다. 지우는 것은 되돌리는 것과 달리 「무엇을 덮는가」를 묻지 않는다 |
| 2 | **시각** | dispatch IT 셋이 약속창을 `Instant.now()` 로 만들었다 | 21시에 돌렸더니 근무창 밖 |
| 3 | **릴레이 리더** | 발행을 보는 IT 가 리더가 되는 것이 클래스 시작 순서에 달려 있었다 | 리더 락이 Redis→advisory 로 옮겨져 **실제로 동작하기 시작**하자. dispatch·order 는 Phase 4-0 에서, **fulfillment 는 2026-09-18 (Phase 5-0)** 에 닫혔다 — 자기 `@DynamicPropertySource` 를 가진 `GeoFallbackIT` 가 둘째 컨텍스트라 릴레이가 둘이었고 락은 하나였다. 발행을 보지 않는 IT 가 자기 자리에서 끄고, 보는 IT 둘은 **`lead()` 가 `LEADER` 인가**를 첫 어설션으로 묻는다 |
| 4 | **플래너 통계** | 통계 없는 테이블에서 플래너가 *짐작으로* 인덱스를 골랐다 — 50행에서 순차 스캔이 옳다 | CI 에서 autoanalyze 가 먼저 돌아 |
| 5 | **컷오프 상한** | `GeoFallbackIT` 의 시각 리터럴이 `isStale` 24시간을 넘겼다 — 작성한 날로부터 25시간짜리 | 이틀 뒤 열 캠프 전부 배차 불가 |
| 6 | **배정 동률** | 한계비용이 같을 때 `ORDER BY code` 순서로 차를 골랐다. cold-chain 공허성 검사의 통과·실패가 **시각과 시드 배분**에 달려 있었고, **CI 의 이전 통과는 시각 운이었다** | 근무조를 나누자 한 대가 웨이브를 흡수하게 되어([ADR-030](adr/ADR-030-night-shift-seed.md)) 드러남 |
| 8 | **실행 순서** | 클래스·컨텍스트의 **시작 순서가 보장된다**고 암묵적으로 기대했다 — 축 3 의 fulfillment 쪽이 그 위에 서 있었다 | **순서 자체가 실행마다 달랐다** (2026-09-18, Phase 5-0): 같은 두 클래스를 두 번 돌렸더니 `GeoFallbackIT`→`WaveLifecycleIT` 와 그 반대가 각각 나왔다. 즉 초록의 근거는 「순서」보다도 얇은 **타이밍**이었다. 음성 표본은 그 타이밍을 고정해 만든다 — 앞 컨텍스트가 `lead()` 로 락을 확실히 쥐게 하자 뒤 클래스 일곱 개가 전부 `LEADER` 대 `FOLLOWER` 로 실패했다. **같은 축이 Phase 5-1b 에서 한 번 더 나왔다** (2026-09-19): `ShipmentEventPartitionIT` 의 회전 검사가 2035년 기준 시계로 `rotate()` 를 부르며 보존 경계보다 앞선 파티션을 **전부** 드롭한다 — 오늘 것까지. `ScanApiIT` 가 그동안 통과한 근거는 **클래스 이름 순서**(`S-c` < `S-h`)뿐이었고, 뒤에 붙은 `TrackingPublishIT` 는 그 운이 없어 `no partition of relation "shipment_events" found for row` 셋으로 드러났다. 고친 방식은 축 1·2 와 같다 — **쓰는 쪽이 자기 자리에서 만든다**(`ensure` 는 멱등이다). 되돌리거나 지우는 쪽을 고치지 않는 이유는 그 드롭이 그 테스트의 <em>검사 대상</em>이기 때문이다. **세 번째가 Phase 6-0c 에서 나왔다** (2026-09-23): `RouteStopOrdersIndexIT` 의 `@AfterEach` 가 `route_plans` 를 지우면서 `plan_explanations` 를 지우지 않았다 — 자기가 만드는 행은 아니지만 FK 로 그 계획을 참조하므로, *앞서 돈 클래스가 남긴* 설명 행이 있으면 FK 위반으로 터진다. 같은 소스셋의 다른 IT **여섯은 전부** 자기 정리에 그 표를 넣고 있었고 이 하나만 빠진 채 통과하고 있었다. 드러난 계기가 이 축의 정확한 발현 형태다 — **클래스 하나(`RouteProgressFallbackIT`)가 사라지며 포크에 담기는 배치가 바뀌었다.** 코드는 한 줄도 그 IT 를 건드리지 않았고, 통과의 근거였던 것은 *다른 클래스가 앞에서 치워 주는 순서*였다. 고친 쪽은 삭제가 아니라 그 정리다 |
| 7 | **검사 대상 집합** | 실현 가능성 기준이 `{SMALL, MEDIUM, LARGE}` 를 **열거**했다 — `peak` 은 목록에 없었고, 목록에 없다는 사실은 어디에도 나타나지 않았다 | 병렬화 게이트를 재려고 `peak` 을 돌렸더니 총비용의 88%가 미배정 페널티(stop 8,411 > 슬롯 7,200) |
| 9 | **환경이 결함을 가린다** | 검사가 보려는 성질을 **환경이 기본값으로 만족**시키고 있었다. 둘은 같은 얼굴이다 — ① Phase 1: 개발 기계의 `Clock.systemUTC()` 가 나노초를 내지 않아 저장 정밀도(마이크로초) 불일치가 숨어 있었다 ② Phase 5-1a: 컨테이너 세션이 UTC 라 파티션 경계 검사가 **함수가 세션 존을 써도 그대로 통과**했다 | **환경을 일부러 어긋나게 만들어 드러낸다** (2026-09-19): 같은 커넥션에서 `SET TIME ZONE 'Asia/Seoul'` 로 만들었더니 경계가 `FROM ('2035-05-09 15:00:00+00')` 로 나와 검사가 실패했다. Phase 1 쪽의 대응은 저장 정밀도로 자른 `Clock` 빈을 `libs/messaging` 한 곳에 둔 것이다 — 양쪽 다 **기본값이 맞춰 주던 것을 검사가 직접 말하게** 하는 형태다. **반대 방향도 있다**: 환경이 바뀌어 결함이 *사라진* 경우다 — [ADR-009](adr/ADR-009-url-path-api-versioning.md) 결정 3 의 음성 표본(`/actuator/health` 의 세그먼트를 버전으로 파싱해 프로브가 깨진다)은 Boot 4.1.x 에서 재현되지 않는다(2026-09-19). 그때 남는 것은 **초록인 채로 아무 말도 하지 않는 검사**이고, 이쪽의 대응은 결정을 방어적으로 유지하되 그것을 지킨다고 *말하던* 줄의 범위를 좁히는 것이다 |

6번이 이 목록의 요점을 가장 잘 보여 준다. 검사는 옳았고 코드도 "틀리지" 않았다 — 다만 답을
정하는 자리가 비어 있었고, 그 빈자리를 **어댑터의 정렬 순서**가 메우고 있었다. 채운 것이
[ADR-031](adr/ADR-031-least-capable-first-tie-break.md) 이다. 그리고 이 결함을 잡은 것은
벤치마크가 아니라 **데모의 공허성 검사**였다 — 벤치마크 수치는 하나도 바뀌지 않았다.

**축이 낳은 규칙 둘** (2026-09-18, Phase 5-0 — 전문은 `CLAUDE.md` 「코딩 컨벤션」).

1. **픽스처는 되돌리지 말고 만들고 지운다.** 지우기는 되돌리기와 달리 「무엇을 덮는가」를 묻지
   않는다. 축 1 의 행이 **전역** 룰이었다는 사실이 이 규칙의 근거다 — 되돌림이 한 번 어긋났을
   때의 반경이 전 캠프였다.
2. **공유 자원을 쓰는 IT 는 자기 자리에서 켜고 끈다 — 기반 클래스는 그 속성에 의견을 갖지
   않는다.** 기반의 기본값은 하위 클래스가 말하지 않는 조용한 전제가 되고, 자원이 하나뿐이면
   (advisory lock) 켜 둔 IT 들이 서로를 조용히 막는다(축 3·8).

**결정론**: 최적화 테스트는 seed 고정. 시간은 `Clock` 주입으로 제어. Testcontainers 재사용(`testcontainers.reuse.enable=true`)으로 로컬 실행 시간 단축.

**시계는 하나다**(불변규칙 12). 도메인 전이가 `Clock` 에서 받은 시각으로 `updated_at` 을 옮기면,
어댑터에 두 번째 시계를 두지 않는다 — JPA `@PreUpdate`·`@UpdateTimestamp` 도, DB `DEFAULT now()`
도 쓰지 않는다. 시계가 둘이면 "그 주문에 마지막으로 무슨 일이 있었나" 의 답이 저장 시각으로
덮이고, 그것을 기준으로 도는 보존 정리([ADR-023](adr/ADR-023-fulfillment-retention.md))가 주입된
시계로는 재현되지 않는다.

---

## 14. CI/CD와 배포

**ci.yml (PR·main)**: checkout → JDK 25 → Gradle 캐시 → `./gradlew check`(단위+ArchUnit+계약+JaCoCo 게이트) → 통합 테스트(Testcontainers, Docker 서비스) → `benchmark medium` 회귀 게이트 → 이미지 빌드(Buildpacks, ADR-013) → Compose 스모크(주문 20건 E2E) → 결과 아티팩트(리포트, OpenAPI).

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
| 004 | **비교 대상은 다른 솔버가 아니라 불가능의 경계다** — 자체 휴리스틱(`sweep-greedy-nn+ls`) 기본, `timefold` 는 등록하지 않는다. 대신 고정비 하한 열(상시) · 그림자 원장 여덟 줄 · 구성 계열이 다른 두 전략 비교. **다시 여는 조건 셋을 미리 적는다** | OR-Tools(JNI·배포 부담), Timefold 단독(블랙박스로는 알고리즘 역량 증명 약함), Phase 4 안에서 비교 강행(번역 검증 비용 > 비교의 값 — 모델의 차이를 재게 된다), 조건 없이 Phase 7 stretch 로 이월(기억에 맡기는 일) | [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md) |
| 005 | Redis `SET NX` 락 + DB 낙관적 락 이중화 | PostgreSQL advisory lock(**서비스 <em>간</em> 락에 한한 기각 사유다** — 2026-09-05 각주), Redisson | — (Phase 2 예정) |
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
| 026 | 취소는 최적화 트리거가 아니라 입력 변경 — stop 을 죽이고 순서는 두고 시간만 재전파 | 취소를 §6.8 재계획 트리거로(같은 판단을 하는 회로가 둘), 재시퀀싱(기사가 보고 있는 순번이 바뀐다), 취소된 stop 을 페이로드에서 삭제(부재는 값이 아니다 — 취소·이동·발행 누락이 구별되지 않는다), 부분 취소를 stop 상태로 표현(통합된 stop 은 여전히 방문한다) | [ADR-026](adr/ADR-026-dispatch-cancellation-window.md) |
| 038 | **고정비 하한은 총비용의 하한이 아니다** — 룰이 stop 상한을 스스로 말하고(`routeStopCap()`, 답하는 룰들의 **min**) · 리포트에 **완화 문제의 고정비 하한**을 상시 열로(제약 조합 × 자원, 분수 허용, 축별 max — `large` 2,500,000 대 하한 1,738,000) · **그 열은 총비용의 하한이 아니다**(고정비를 하한까지 밀면 미배정이 3~4배, 전부 위험물 — 빈 좌석은 희소 능력이 앉을 자리다) · 클러스터러 축 정정은 **만들었고 재고 넣지 않았다** — 보류의 근거는 `large` 의 1.65%p 가 아니라 **얽힘**(4-8 과 4-17 이 둘 다 「어느 stop 이 한 라우트를 공유하는가」를 바꾼다, 순서는 17 → 8) · 설계대로 배정(B)도 단독으로 넣지 않는다 — §6.5 3단계가 **좌석 예약 없이는 불완전**하다고 스스로 말한다 | 축만 고쳐서 지금 넣기(**얽힘** — 4-17 이 뒤집을 상태를 기준으로 재기준을 남기게 된다; `large` 가 나빠지는 것은 이유가 아니라 증상), 클러스터 크기 상수 튜닝(이 항목을 만든 오류의 재발), `priority-boost` 의 `÷ position` 제거(**정책 변경** — 재는 자를 고치는 일, **4-18** 로 연다), 하한을 정수 최적해로 올리기(하한이 아니게 된다), 총비용 하한으로 확장(완화가 원 문제만큼 어려워지면 두 번째 최적화기다), 위험물을 [ADR-031](adr/ADR-031-least-capable-first-tie-break.md) 능력 순위에 되돌리기(측정으로 뺀 것은 같은 급의 측정으로만 되돌린다 — 4-17) | [ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) |
| 039 | **좌석은 능력이 아니라 제약 조합에 예약한다** — 통합 후 stop 의 조합을 계획 시작 시점에 세고 조합별로 `min(수요, 그 조합을 실을 수 있는 stop 슬롯)` 을 **차량 인덱스 순 라운드로빈**으로 예약 · 덜 특정한 수요는 더 특정한 예약에 앉지 못하고 반대 방향은 **자기 버킷이 소진됐을 때만** · 일반 조합에는 예약하지 않는다(아무도 막지 않는 항등) · **예약은 배정 단계의 것**이라 재삽입 전에 풀리고 따라서 **미배정의 사유가 될 수 없다** — 대신 푸는 순서를 정한다(재삽입 동률 둘째 키 = 앉을 자리가 적은 수요부터, [ADR-031](adr/ADR-031-least-capable-first-tie-break.md) 의 수요 쪽 쌍대) · 밀린 일반 stop 은 배정 설명에 `reserved-seat` · 재기준 1,490,513 → **1,136,026** · 3,919,106 → **3,893,515** · 8,276,130 → **8,281,646** · 22,341,428 → **21,774,900**, 미배정 9·0·1·10 → **0·0·0·1** · 그리고 **상한은 「그 항」의 상한이지 「그 변경」의 상한이 아니다**(실제가 상한을 넘었다 — §6.9) | 능력별 예약(`peak` 에서 no-op — 여유 287 이 조합 여유 5 를 가린다), 희소 클러스터 먼저 배정(클러스터 100%가 위험물을 포함해 no-op), 정렬 키 순서 변경(예약은 하드 용량이라 키 위에서 성립한다 — 4-8·4-18 의 몫), 하드 룰로 추가(예약은 계산되는 값이지 운영자가 적는 값이 아니다 — §6.3), 재삽입까지 예약 유지([ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) 의 「빈 좌석」 교훈을 거꾸로 만든다), `large` 가 지므로 넣지 않기(결과를 보고 고르는 것 — 폭이 음수인 것은 알고 들어갔고 이유는 얽힘이다) | [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) |
| 040 | **`priority-boost` 는 순번이 아니라 시각으로 감쇠한다** — `bonusKrw × priority × τ ÷ (τ + t)`, `t` 는 **계획 시작** 대비 계획 도착 분, `τ = halfLifeMinutes` 기본 **12분**(그림자 계측에서 `÷ position` 과 총 기여가 같아지는 값) · 기준점이 라우트 출발이 아닌 이유는 **라우트마다 0점이 다르면 「쪼개면 앞자리가 늘어난다」가 시각으로 되살아나기** 때문 · 자를 바꿨으므로 **전 데이터셋 재기준(`baseline-nn` 절대값 포함)** · 판단 기준에서 **「어느 정의가 `large` 를 15% 넘기는가」를 뺐다** | `÷ position` 유지(서비스가 좋아진 계획을 +64,018원으로 벌한다 — 총비용 차이 −58,084보다 크다), 약속창 시작 기준(우선 stop 의 76%가 창 전에 도착해 **상수 보너스**가 된다 — `÷ position` 을 넣은 이유를 되살린다), 정규화 순번(쪼개기만 고친다 — 라우트 수·길이가 같은 비교에서도 (a)의 80%를 움직인다), `bonusKrw` 축소(방향이 틀린 항은 작게 만들어도 틀렸다), 라우트 출발 기준(0점이 라우트마다 다르다) | [ADR-040](adr/ADR-040-priority-boost-decays-in-time.md) |
| 041 | **「차 한 대 몫」에는 stop 슬롯이 들어간다** — 목표 클러스터 수 = `min(차량 수, max(중량, 부피, ceil(stop 수 / routeStopCap)))` · 룰의 파라미터를 읽는 것이 아니라 **룰이 답하는 질문**을 하나 더 묻는다([ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)) · **클러스터 수 상한(차량 수)은 남긴다** — 셋을 한꺼번에 바꾼 판이 진 이유가 그 상한 제거였다(`peak` 클러스터 121개 > 차량 88대, +1,507,476원) · 그리고 **「빈 차를 먼저 본다」를 설계서에 올린다**(그 휴리스틱만 뺀 변형이 `peak` +2,203,845원 · 미배정 2 → 70) · 재기준 −26.15% → **−26.60%** · −16.12% → **−17.37%** · −14.56% → **−14.93%** · −23.37% → **−24.18%** | 축·차량급·상한을 한꺼번에 바꾸기(`large` −12.87% · `peak` −18.66% — 범인은 상한 제거였다), 이분법에 계속 맡기기(클러스터러의 오류를 메우는 것이지 설계가 아니다 — 이분법을 빼면 `small` 미배정 3), 클러스터 수 상한 제거(남는 클러스터가 이미 실은 차에 얹혀 지그재그), 가장 작은 차량급 기준(묶어서 재지 않으려고 남겼다 — 따로 잰다), FAST 가 나빠지므로 넣지 않기(FULL 이 기본이고 네 데이터셋을 다 이긴다 — 열화가 더 나빠지는 것은 열화의 성질이다) | [ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md) |
| 042 | **savings 의 병합은 제약 조합을 안다** — `savings-cw+ls` 구성 단계. 쌍은 **개선 단계와 같은 K-최근접 표**([ADR-032](adr/ADR-032-local-search-budget-and-approximations.md), K=20 — 완전 목록은 `peak` 에서 3,500만 쌍) · 병합 가능성은 **합집합 조합을 덮는 차량 중 가장 큰 것**으로 · [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) 의 **집계 좌석 불변식을 구성 단계로** 옮긴다(예약은 배정의 장치인데 CW 에서 배정은 라우트가 다 만들어진 뒤에 온다) · 뒤 단계(재삽입·개선)는 **같은 클래스** · 라우트를 뒤집지 않는다 | 「가장 큰 차량」 기준 단순 병합([ADR-038]·[ADR-039] 의 결함을 CW 안에서 되살린다 — 지는 이유가 「구성 방식」이 아니라 「희소 좌석」이 된다), 완전한 savings 목록(3,500만 쌍), K 를 이름에 넣기(표가 읽히지 않는다), 라우트 뒤집기(순서는 5단계의 일), 다중 패스(재 봤다 — 두 번째 패스가 한 건도 더 잇지 못한다), 부착에서 재시퀀싱(구성이 정한 순서를 배정이 덮는다), 전용 재삽입·개선(§6.6 이 이미 기각한 「두 구현의 차이」) | [ADR-042](adr/ADR-042-savings-merges-are-class-aware.md) |
| 044 | **끝점은 전부 본다 — 근사는 stop 이 많을 때의 것이지 라우트가 적을 때의 것이 아니다** — savings 구성에 2단계를 붙인다: 1단계(K-최근접) 뒤 남은 라우트들의 (꼬리, 머리) 쌍을 **전부** 만들어 같은 게이트로 잇고 고정점까지 돈다 · 쌍 예산 `R(R−1) ≤ n·K`(성능 가드이지 동작 게이트가 아니다 — 부등식이 참인 구간의 쌍은 K 표가 이미 본 것이다) · 상한에 찬 라우트는 후보에서 뺀다 · **`peak` 라우트 216 → 90**(stop 하나짜리 67개가 0 이 된다), 밀린 라우트 128 → 2, FULL 이 13,018 ms 에 수렴 · `large` 차량 40 → 35 | 전체 K 키우기(개선 단계 K 도 움직여야 해 비교가 표 크기를 잰다 · 157 까지밖에 안 내려간다 · **206 ms 로 더 비싸다**), 끝점만 K_end=50·100(같은 이유로 비싸고 상한 미달), 라우트 뒤집기(순서는 5단계의 일), 2단계에서 게이트 느슨하게(집계가 2단계에서도 638건을 거절한다), 부착이 한 차에 둘(증상을 고친다 — 90개가 88대에 맞으므로 지금은 불필요), 쌍 예산 없이(못 이은 입력에서 `O(n²)` 가 마감을 먹는다) | [ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) |
| 052 | **위임 클라이언트는 커밋된 계약에서 만든다 — 채택 기준을 먼저 적는다** — 후보 하나(`spring` 생성기 · `spring-http-interface`), 기준 다섯(표준 템플릿 · 문서화된 옵션만 · 생성물 그대로 컴파일 · Jackson 3 왕복 · 새 런타임 의존 없음) — 하나라도 거짓이면 손으로 쓴 인터페이스 + YAML 대조 테스트 — **채택**(7.25.0, 다섯 기준 모두 참 · 왕복 32개) · 토큰은 스크립트가 찍고 ops-api 는 검증만 · 감사 행은 위임 **전에** `PENDING`, 응답을 못 받으면 `UNKNOWN` · 감사 id 를 상관 헤더로 | 계약 없이 컨트롤러 소스에서, 살아 있는 `/v3/api-docs` 에서 생성(입력이 커밋에 남지 않는다), 생성물 커밋(서로를 비추는 목록이 하나 는다), 개발 전용 로그인 엔드포인트(프로필이 꺼져 있다는 조용한 전제), 위임 뒤 한 번만 기록(죽으면 기록이 사라진다) | [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md) |
| 051 | **읽기 모델의 행은 먼저 온 사실이 만든다 — 부재는 값이 아니다** — 축 규칙([ADR-017](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md))의 **다섯 번째 자리**이고, 앞의 넷과 달리 **행 하나에 여러 토픽이 쓴다**(`rm_orders` 에 여섯 — 2026-09-24 DDL 정정 뒤 일곱 · `rm_waves` 에 넷 · `rm_routes` 에 넷) — 그래서 「이 전이를 받는가」 앞에 **「그 행이 아직 있기는 한가」**가 하나 더 있다 · 핸들러는 전부 **upsert** 이고 「행을 만드는 핸들러」를 두지 않는다(늦게 온 `UPDATE` 는 0 행을 갱신하고 **예외 없이 성공**한다) · **자기 칸만 쓴다** — 모르는 칸에 `NULL`·`0`·`false` 를 넣지 않는다(`false` 는 「위험하지 않다」라는, 아직 아무도 하지 않은 주장이다) · 개수는 증감이 아니라 **집계**다([ADR-025](adr/ADR-025-wave-admission-share-lock.md) 의 「카운터 드리프트가 구조적으로 불가능」과 같은 형태 — `delivery.status` 가 `order.dispatched` 보다 먼저 오면 올릴 라우트가 없다) · 「아직 안 왔다」는 DLQ 도 `rejected` 도 아니다(§4.6) · 관측 근거는 **순서를 뒤섞는 IT** 이고 토픽을 **빼는 방식**으로 돈다([ADR-050](adr/ADR-050-route-departure-is-an-event.md) 이 방금 열한 번째를 더했다 — 열거였다면 그 토픽은 검사 밖이었다) · 근거는 **관측(재현됨)**(2026-09-24 — 기각한 반대안 셋을 임시로 넣자 셋 다 씨 1 에서 사실을 조용히 잃었다) | 정방향 전제 + 어긋나면 DLQ(정상 트래픽을 DLQ 로 보내고 화면의 정확성이 그날의 컨슈머 랙에 걸린다), 행이 없으면 재시도(그 6초가 다른 파티션의 지연과 아무 관계가 없다 — ADR-017 이 같은 제안을 같은 이유로 기각했다), 키별 재정렬 버퍼(**완료 조건이 없다** — 끝내 오지 않는 것이 정상인 토픽이 있고, 지연이 열한 소비자 랙의 최소가 아니라 최대가 된다), 전 토픽 단일 스레드 소비(직렬화는 순서가 아니다 — 아무것도 사지 않고 처리량만 판다), 골격 행에 기본값 채우기(**없는 사실을 지어내는 일** — `NULL` 은 「아직 모른다」라는 참인 말을 하지만 기본값은 거짓인 말을 한다), `rm_*` 없이 동기 조회(불변규칙 4 · ADR-012), ADR 없이 코드에만(이 규칙은 **하지 않는 일**들이라 코드에서 보이지 않는다 — 가장 먼저 「`SET (…) = EXCLUDED.(…)` 로 줄이자」가 들어온다) | [ADR-051](adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) |
| 050 | **라우트 출발은 이벤트다 — 출발이 첫 편차의 출처이기 때문이다** — `dawnline.delivery.route-departed.v1`(키 `routeId`, 소비자 **ops 뿐**) · 근거는 화면이 아니라 **사실의 가시성**이다: 지금 출발을 아는 것은 tracking 뿐이라(`ScanType.isPublished()` 가 `DEPARTED_CAMP` 를 뺀다) ops 는 첫 `ARRIVED` 가 올 때까지 「출발 안 함」과 「출발했는데 아직 도착 없음」을 구별하지 못하고, **그 구간이 운영자가 개입할 수 있는 마지막 창이다**(아직 안 나간 차는 다시 짤 수 있다) · **라우트 하나에 이벤트 하나** — 반복하지 않는다는 이유가 말하지 않을 이유였던 적은 없다([ADR-024](adr/ADR-024-plan-completed-event.md) 의 거울상: 사실의 단위와 토픽의 단위를 맞춘다) · 페이로드 여섯 칸(`routeId`·`campId`·`revision`·`plannedDeparture`·`departedAt`·`stopCount` — 2026-09-24 `stopCount` 를 빼 다섯: 부재를 다른 출처로 메우지 않는다)은 **마이그레이션 없이** 나온다 · `revision` 을 싣는 이유는 「어느 개정본의 계획에 대해 늦었나」를 말해야 하기 때문 · 스키마·예시·토픽·발행은 **소비자가 먼저**(묶음 B, ops 의 `rm_routes`) | 정의하지 않는다(더 단순하지만 그 대가가 **마지막 개입 창을 숨기는 것**이다 — `rm_routes` 는 없는 사실을 만들어 내지 못한다), `delivery.status` 의 `status` 에 `DEPARTED_CAMP` 추가(한 사실이 stop 수만큼 반복된다 — 5-1b 가 발행하지 않기로 한 그 이유), `route.assigned` 에 `departedAt` 을 나중에 채우기(계획 이벤트를 사실로 갱신하면 개정으로 거르는 소비자가 사실을 함께 버린다), ops-api 가 tracking 에 동기 조회(출발은 사건이지 조회 대상이 아니다 — 해상도가 폴링 주기가 된다), 페이로드를 `{routeId, departedAt}` 둘로(편차의 기준선 `plannedDeparture` 가 개정마다 다르다) | [ADR-050](adr/ADR-050-route-departure-is-an-event.md) |
| 049 | **Spring 을 아는 공유 코드는 자기 lib 에 산다, common 은 순수하게 남는다** — `ProblemDetailsAdvice` 가 세 서비스에 거의 글자 그대로 있었고 갈라지는 칸은 `RETRY_AFTER_SECONDS` **하나**였다 · 자리는 **새 모듈 `libs/web`** 이다(`libs/messaging`·`libs/observability` 옆 — 저장소에 이미 그 패턴이 있다) · 훅은 **추상 메서드**라 「이 서비스에는 그런 오류가 없다」는 판단이 코드에 남는다 · ArchUnit 규칙 9(`@ControllerAdvice` 계열은 전부 이 기반을 쓴다 — **열거가 아니라 조건**이라 ops-api 가 스스로 대상이 된다)와 규칙 10(`libs/common` 의 main 은 Spring·JPA 비의존)이 그 둘을 강제한다 | `libs/common` 의 Gradle 피처 변형(가드 둘이 모르는 구조 — `check` 컴파일 의존과 JaCoCo `classDirectories` 를 손으로 고쳐야 한다), `libs/common` 의 main 에 그냥 넣기(`tools/benchmark` 가 Spring 없이 쓴다), 사본 셋 유지(네 번째가 Phase 6 에 있다), `libs/observability` 에 얹기(오류 응답의 모양은 관측이 아니다) | [ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md) |
| 048 | **재계획은 자기 DB 로 푼다 — 페이로드는 트리거다** — 「미완료 stop 만」은 <em>어느 stop 이 남았나</em>만 말하고, 다시 푸는 데는 **기사가 지금 어디에 얼마나 늦게 있나**가 더 필요하다 · 그 편차를 `delivery.at-risk` 에서 읽으면 진실이 «소속은 dispatch · 시각은 tracking» 으로 갈리므로 **V10 `route_stops.actual_at`**(처음 닿은 시각, 덮어쓰지 않는다 — 덮으면 도착이 아니라 완료를 재게 된다)을 5-5 전이가 채우고 편차 = `마지막으로 닿은 stop 의 actual_at − planned_arrival` · 페이로드의 값은 **대조값**이고 60초 넘게 갈리면 `dawnline_at_risk_deviation_mismatch_total` (둘이 갈리는 것이 정보다 — 두 relocate 카운터와 같은 형식) · **편차는 평가 시계를 민다**: 저장되는 `planned_arrival` 은 계획 시계 그대로라 닿은 stop 의 기준선이 재계획을 지나도 안 움직인다(ETA 는 tracking 의 것이다) · 닿은 stop 이 없으면 **모름**이고 `no-anchor` — 출발 지연 at-risk 는 stop 하나 뒤에 닫힌다 · `relocate` 세 조건(현재 위치 이후만 · [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) 조합 게이트 · 두 라우트 모두 재검증·revision 증가, 미출발 차량의 고정비는 `CostModel` 에 **이미 있다**) · 실패는 DLQ 가 아니라 `dawnline_replan_total{outcome}` 다섯 갈래 · `applied` 는 `plan_explanations`(`AT_RISK_RELOCATE`)에 「어느 주문이 어디서 어디로, Δ비용 얼마」 · `no-gain` 은 **소프트 룰까지 포함한 두 라우트 총비용**(§6.1 그대로) | 페이로드를 입력으로(코드 한 줄이지만 진실이 갈린다 — 불변규칙 4 가 허락하는 것과 이 자리에서 옳은 것은 다르다), `deviationSeconds` 를 아예 무시(갈리는 것이 정보다), 전체 재최적화(§6.8 3단계 — 얼어 있는 앞자락을 뺄 방법이 파이프라인에 없다), `planned_arrival` 에 편차 반영(기준선이 사라지고 ETA 를 두 테이블에 적는다), DLQ(고칠 수 없는 것이 재시도된다), 쿨다운을 tracking 의 Redis 하나로([ADR-046](adr/ADR-046-at-risk-is-an-event.md) 가 이미 기각), `actual_at` 덮어쓰기(뜻이 바뀌는데 값을 보아서는 알 수 없다), 편차를 모를 때 0(모름은 0 이 아니다) | [ADR-048](adr/ADR-048-replan-reads-its-own-db.md) |
| 047 | **배송 상태는 사실이고 개정은 계획이다** — **계획은 `(route, revision, seq)` 로, 사실은 `orderId` 로 식별한다**(같은 열쇠를 스캔 API·tracking·dispatch 세 자리가 쓴다 — 스캔 API 는 2026-09-23 에 `orderIds` 필수로 바뀌었고, `DEPARTED_CAMP` 만 라우트의 사건이라 예외다. 찍은 자리가 다르면 적용하고 `dawnline_scan_after_relocate_total` 로 센다) · dispatch 의 `route_stops.status` 가 축 규칙([ADR-017](adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md))의 **네 번째 자리**다 · stop 은 `stopSeq` 로 찾지 않고 **`routeId` 로 좁히지도 않는다**: 다른 라우트에서 찾으면 거기 적용하고 `dawnline_status_after_relocate_total`(= §6.8 경합 창의 크기)로 세며, **어느 라우트에도 없을 때만** stale · **개정 번호로 거르지 않는다**: `route.assigned` 는 계획이라 옛 것을 버려야 하지만 `delivery.status` 는 사실이라 버리면 일어난 일이 사라지고, 그것이 §6.8 「미완료 stop 만」이 읽는 값이다(근거: **관측(재현됨)** — 2026-09-23 에 5-3 의 `ReplanIT` 이 재현 수단을 만들었다. 그전에는 「추정」이었고 돌릴 재계획이 없었다) · `CANCELLED` stop 의 상태는 무시하고 `dawnline_scan_after_cancel_total` 로 센다(tracking 과 같은 이름, 자리로 갈린다 — 한쪽만 오르는 것이 정보다) · `FAILED` 도 종결 · 이 전이가 §6.10 넷째 분기를 처음으로 발화 가능하게 한다(「구조적으로 0」 문단을 닫는다) · `route_stop_orders (order_id)` 인덱스 하나(V9, 빈도가 취소마다→방문마다로 바뀌었다) | `stopSeq` 로 찾기(개정이 뜻을 바꾼다), **「이 라우트에 없으면 stale」(첫 판 — `seq` 만 버리고 `routeId` 를 남긴 것은 같은 오류의 절반이고, 카운터가 갈리지 않는 것이 그 신호였다)**, `revision` 을 계약에 더해 ADR-045 를 그대로 옮기기(대칭은 이름의 대칭이지 의미의 대칭이 아니다), `CANCELLED` stop 을 `COMPLETED` 로 옮기기(계획 테이블을 배송 원장으로 쓰면 §6.8 과 §6.10 이 다른 질문의 답을 읽는다), stop 애그리거트 메서드(120 stop 을 메모리로 올린다 — 대신 도메인 순수 함수 + §13 매핑표) | [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) |
| 046 | **at-risk 는 사건이고, 쿨다운은 알림 수를 지킨다** — 위험이 커지면 다시 발행하고(쿨다운이 주기) **사라지는 경우는 알리지 않는다**(재계획을 취소할 방법이 없고 해소는 ops 의 ETA 가 보여 준다) · 페이로드는 위험한 stop 이 아니라 **남은 구간 전부** + stop 마다의 `atRisk`(여유 15분은 tracking 의 정책이다) · **쿨다운 둘은 집이 다르다**: tracking=Redis/알림 수, dispatch=DB `routes.last_replanned_at`/정확성 — 「멱등 소비자가 흡수한다」는 틀렸다(두 at-risk 는 `eventId` 가 달라 둘 다 처음 보는 이벤트다, §7.2 정정) · Redis 장애에는 **발행한다**(fail-open, `dawnline_at_risk_cooldown_bypassed_total`) | 위험 해제 이벤트(소비자가 할 일이 없다 — 재계획은 되돌릴 수 없다), at-risk 를 라우트의 **상태 칼럼**으로 (갱신을 놓친 라우트가 조용히 안전해지고, 상태로 두면 「해제」가 자연스러워 보인다), 쿨다운을 DB 로 옮겨 tracking 이 정확성까지(막아야 할 중복은 재계획이고 그것은 dispatch 의 것이다), 쿨다운 없이 매번 발행 (`peak` 에서 라우트당 최대 90건이 거르기 **전에** 토픽·컨슈머·`processed_events` 를 지난다) | [ADR-046](adr/ADR-046-at-risk-is-an-event.md) |
| 045 | **개정 번호는 라우트의 것이다** — tracking 은 `route_revisions`(route_id PK)와 비교해 낮거나 같은 `revision` 을 무시한다 · §8.5 가 적은 「routeId + revision」 의 자리를 §5.4 DDL 이 비워 두고 있었다 · `processed_events` 와 겹치지 않는다(저쪽은 같은 이벤트의 재배달, 이쪽은 옛 개정의 뒤늦은 도착) · 보존 정책이 없다는 것을 **적어 둔다** — Phase 6 에서 `shipments` 와 함께 정한다 | `shipments` 에 `route_revision` 컬럼 + `MAX(...) WHERE route_id = ?` (§6.8 의 `relocate` 가 라우트를 비우면 비교할 값이 NULL 이 되고, 그때 DLQ replay 가 **이미 옮겨간 주문을 되돌린다**), shipment 행마다 비교(번호가 라우트마다 독립이라 A(5)→B(2) 이동이 **역행으로 읽힌다**), 비교 없이 마지막 도착본 적용(§6.8 4단계가 금지한다 — replay 가 있는 시스템에서 「마지막에 도착」은 「마지막에 일어난」이 아니다), `routes` 전체를 프로젝션(안 쓰는 칸이 낡았는지 아무도 모른다) | [ADR-045](adr/ADR-045-revision-comparison-is-per-route.md) |
| 043 | **기본 전략은 `peak` 이 수렴할 때까지 바꾸지 않는다** — `savings-cw+ls` 가 네 데이터셋에서 싸지만(−29.27% · −19.24% · **−19.30%** · `overload` 111,436,423) `peak` 에서 30초 예산에 **잘리고 잘린 값이 실행마다 다르다**(20,940,782 ~ **23,277,318** — 넷째는 마감이 배정을 끊어 96 stop 이 `plan-deadline`, 기본보다 +1,767,471원) · **바꾸는 조건을 미리 적는다 — 벽시계가 아니라 구조: `peak` 구성 라우트 수 ≤ 차량 수 × 1.2(= 106, 지금 216)**. 부족분 3초의 97%가 재삽입이고 그 부하를 정하는 것이 라우트 수다 (조건은 2026-09-17 「30초 수렴」에서 교체 — 기계가 바뀌면 참·거짓이 바뀌는 조건은 재현되지 않는다) · 게이트 목록에는 넣는다 · DoD 는 **−18.20%(savings, 수렴) / −14.93%(기본)** 를 함께 적는다 · **2026-09-17 조건 충족**([ADR-044] 로 라우트 90 · `peak` 13,164 ms 수렴 · 기본보다 −772,025) **그런데 바꾸지 않는다 — 이유가 바뀌었다**: 같은 변경이 `small` 을 기본보다 **+13.4%** 로 만들었다. 남은 조건은 「`small` 에서 savings ≤ 기본」이고 그것을 만드는 항목이 4-20 | 바꾸기(재현되지 않고 그 방향이 한쪽이 아니다), `peak` 예산 60초로(자를 옮겨 통과시키는 일), 크기별 기본값(「왜 이 전략인가」 축이 하나 더 생긴다 — 모드 축이 이미 답한다), 열화 사다리에 맡기기(사다리는 *직전* 계획이 조건이라 첫 계획은 언제나 전체 예산으로 돈다), 등록하지 않기(§6.6 표에 원래 있고 넷을 이긴다), 게이트 목록에서 빼기(조용히 나빠지는 것을 볼 자리가 없어진다), **구조 조건 폴백**(라우트 수 > 차량 × 1.5 이면 그 계획만 스윕으로 — 비용이 아니라 구조로 고르므로 허용 범위이긴 하나 원인인 끝점 병합 부족을 두고 증상을 우회한다. 4-19 가 실패했을 때의 차선) | [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) |
| 037 | **재삽입은 들어갈 수 없는 자리를 시도하지 않는다** — 하드 룰을 「위치와 무관한가」로 가르고(stop 수·적재·차량 속성 ✅ / 근무창·약속창 ❌), 위치 무관 룰이 거절한 라우트는 자리를 보지 않는다 · 상한을 **구현 전에 그림자로** 쟀다(`overload` 96.8% · `large` 93.2%) · **결과가 한 자리도 안 바뀐다**(`overload` 43.2 → 19.2초, 120초 예산의 수렴값과 같은 답) · 미배정 사유가 룰 이름으로 남는다 | k-최근접 라우트 한정(**휴리스틱** — 빈 차가 멀리 있을 수 있다, 재기준 경로), 잔여 용량을 재삽입 안에서 직접 계산(룰을 코드에 두 번째로 적는 일), 기본값을 「위치 무관」으로 두고 예외만 표시(새 룰이 조용히 대상이 되고 **답이 달라진다**), 속성 클래스 단위 건너뛰기(라우트별만으로 96.8% — 남은 것은 값싼 룰 평가 21만 번, **재검토 지점**) | [ADR-037](adr/ADR-037-reinsertion-prunes-what-cannot-fit.md) |
| 036 | **마감은 계획 전체의 것이다** — §6.7 의 `totalMs` 는 계획의 예산인데 구현은 §6.5 5단계에만 걸려 있었다(`overload` 43.2초 중 61%가 마감 없는 재삽입) · 마감이 오면 남은 것은 **`plan-deadline` 사유의 미배정** · 이것이 없으면 **열화 사다리가 허구**(FAST 로 개선을 다 꺼도 30초가 안 된다) · 베이스라인은 동결이라 대상 밖 · `budgetExhausted` 로 재현 가능 여부를 리포트가 스스로 말한다 | 예산의 뜻을 「개선 단계의 것」으로 좁히기(목표와 예산이 다른 것을 가리킨다), 마감에 예외를 던져 계획을 버리기(웨이브가 통째로 배차되지 않는다 — 미배정보다 나쁘다), FAST 에서 재삽입 생략(열화의 정의가 바뀐다, 그리고 비싸진 것은 미배정이 많을 때뿐이라 모드가 아니라 마감의 일), 재삽입 시도 횟수 상한(상한이 **결과**를 바꾼다 — 마감은 「하지 못한 일」을 남기지 「다른 답」을 만들지 않는다) | [ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md) |
| 035 | **병렬 단위는 클러스터가 아니다, 그리고 벽시계가 물린 실행은 재현 대상이 아니다** — §6.7 의 「클러스터별 시퀀싱·개선은 독립」이 두 군데에서 거짓이었다(배정은 차량 상태를 바꾸며 진행하고, 개선 시간의 **88%가 라우트 쌍**을 본다) · 병렬 단위는 **차량 후보 · 라우트 · 라우트 쌍** · 개선 예산을 클러스터 수로 나누지 않는다 · **재현의 기준은 수렴 종료**이고 동일성을 말하는 자리는 그것을 전제 어설션으로 말한다 · 순서는 **안 훑기 → 게이트(`peak`) → 병렬** (라우트 사이 스캔의 **97.6%가 헛스캔**) | 설계서대로 클러스터를 `ForkJoinPool` 에(배정이 순차 의존이라 **틀린 답**), 클러스터별 독립 개선(88%가 사라진다 — 빨라지는 게 아니라 덜 하는 것), 분해표만 보고 바로 병렬화(`large` 는 예산의 19%만 쓴다 — 시간이 어디 있는지와 문제인지는 다른 질문), 노드 단위 don't-look bits(근사라 §6.9 의 모든 수치가 재기준 대상) | [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) |
| 034 | **열화는 사다리다, 그리고 왜 열화했는지가 계획에 남는다** — FAST 는 §6.5 5단계 하나만 끈다(전략 이름은 그대로) · **랙만 FAST, 예산 조건은 개선 예산 절반**(후속 정정 — 둘을 같은 처방으로 묶은 것이 45배 비싼 처방이었다: +0.21% 대 +9.4%) · 랙은 `Consumer#currentLag` 로 재고 **모름을 0으로 접지 않는다** · 사유는 `route_plans.mode_reason` 에 · 사람이 지정한 FAST 는 열화가 아니다 | 별도 전략으로 전환(§6.6 이 이미 기각 — 비교표가 「두 구현의 차이」를 재게 된다), Micrometer 게이지에서 랙 읽기(제어 입력을 관측 지표에서 — 이름이 바뀌면 `NaN` 이 「랙 없음」이 된다), 랙 조건 빼기(버스트를 놓치고 FULL↔FAST 진동), 직전 계획을 인메모리로(재기동에 사라진다), 사유를 카운터 라벨로만(집계는 「이 웨이브는 왜」에 답하지 못한다), 히스테리시스 임계(랙 조건이 이미 그 일을 하고, 정정 뒤 아랫단의 진폭은 1% 대다), `PlanMode` 에 세 번째 값(계약 변경이고, 이 단은 「무엇을 생략했나」가 아니라 「얼마나 했나」다), 계수를 전체 예산에 곱하기(그리디가 오래 걸린 날 개선이 음수 예산을 받는다), `(camp_id, finished_at)` 인덱스(218배지만 5.4 ms → 0.025 ms 로 기준의 1/10 아래 — **재검토 조건**과 함께 기록) | [ADR-034](adr/ADR-034-degrade-mode.md) |
| 033 | **겹친 제약은 한 대에 몰리지 않는다** — 기준(제약 조합별 수요 ≤ 그 조합 차량 용량의 80%) · 능력 분포를 규칙으로(위험물 20%, 그중 절반 냉장) · 통합 키에 제약 클래스 | 그대로 두기(총비용의 34%가 누구도 건드릴 수 없는 상수라 비율의 분모가 부풀어 있다), 데이터셋만 고치기(자가 다른 방향으로 휜다 — `baseline-nn` 이 늘어난 차량을 못 써 −43.98% 가 나왔다), 통합 키만 고치기(냉장∧위험물 차량 1대라는 구조적 결함은 그대로), 필요할 때만 분할(관측되지 않은 압박을 위한 복잡도 — **재검토 지점**으로 기록) | [ADR-033](adr/ADR-033-constraint-classes.md) |
| 032 | **국소 탐색은 근사로 후보를 줄이고 예산은 패스 단위로만 끊는다** — 이웃 표(K=20) + 거리 선별, 패스는 통째로 적용되거나 통째로 버려진다 | 전수 평가(`large` 에서 한 패스도 못 돌아 개선 0), 비용 델타 근사식(룰을 코드에 두 번째로 적는 일), 이동 단위로 예산 끊기(같은 입력에 다른 답), `Clock` 주입(예산은 경과이지 시각이 아니다) | [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md) |
| 031 | **배정 동률은 「능력이 적은 차 먼저」** — 비냉장 < 냉장 < …, 마지막 키는 id(재현성). 냉장 프리미엄 +7,000원 | 위험물까지 아껴 두기(**재 보고 뺐다** — 미배정 99→115, 비용 +3.9%), 데모 시나리오 키우기(데이터로 테스트를 통과시킨다), 공허성 검사 약화, 시드에서 냉장을 첫 자리에서 치우기(`ORDER BY code` 의존은 그대로) | [ADR-031](adr/ADR-031-least-capable-first-tie-break.md) |
| 030 | **부록 A 에 야간 근무조** — 캠프당 야간 8대(23:00–08:00)·주간 12대(09:00–22:00), 냉장·대형은 두 조에 배분. 근무 시작 전에는 출발하지 않는다 | 기사 단위 로스터(실제 운영의 방향이지만 모델 변경 — **다음 단계**로 기록), 계획 시각 주입(테스트가 모델의 구멍을 가린다), 그대로 두기(CI 가 하루 8시간 빨갛고 그 빨강이 시각에 따라만 보인다) | [ADR-030](adr/ADR-030-night-shift-seed.md) |
| 029 | **최적화기 I/O 경로는 ORM 이 아니라 벌크** — 후보는 읽기 전용 프로젝션, 결과는 JDBC 배치, 상태 반영은 집합 UPDATE | `FlushMode.COMMIT`(증상만 숨기고 세션에 5,001개가 뜬 원인은 그대로 · 같은 트랜잭션의 네이티브 질의가 미반영 변경을 못 보는 read-your-writes 위험을 설정 한 줄로 전역에 들인다), 그대로 두기(30초 예산 안이지만 여유 12% 이고 다음 작업이 계획 시간을 늘린다) | [ADR-029](adr/ADR-029-optimizer-io-is-bulk-not-orm.md) |
| 028 | **미배정 정책 하나** — 우선도는 계약이 아니라 사실에서 파생(`promiseRevised` +2 · `requiresCold` +1), 자리는 페널티가 비싼 것부터, 오르는 비용이 페널티보다 쌀 때만 싣는다. 탐욕 뒤 + 국소 탐색 뒤 두 번 | 계약에 `priority` 추가(무인증이라 클라이언트 값을 믿을 수 없다), `serviceTier` 에서 파생(한 웨이브 = 한 티어라 상수), 파생값만 저장(왜 이 우선도인지 답할 수 없다), 근거만 저장하고 계획 시점 계산(계획 중인 웨이브가 흔들린다), 선택과 재삽입을 따로 두기(같은 규칙이 두 벌), 밀어내기(남은 병목은 예산이 아니라 실행 가능성이다) | [ADR-028](adr/ADR-028-unassigned-policy.md) |
| 027 | outbox 릴레이 리더 락 = **PostgreSQL advisory lock**(전용 장수 세션), 리더를 모르면 발행 중단 | 그대로 두기(전제를 지키는 것이 배포자의 기억뿐), **Redis `SET NX`**(2026-09-05 에 한 번 채택했다가 정정 — 조정을 서비스 밖으로 내보내 발행 가용성이 Redis 에 묶였고 폴백 없는 예외를 §7.2 에 만들었다), 판정 불가를 팔로워로 접기(대시보드에서 정상과 장애가 구별되지 않는다), `partition_key` 해시 분할(인스턴스 수가 바뀌는 전환 구간에 같은 문제), Kafka EOS(ADR-006 기각 + 두 프로듀서의 순서를 정해 주지 않는다), 리더 선출 라이브러리(의존 추가), 풀에서 빌린 커넥션에 락 잡기(반납하면 락이 풀 안에 남는다) | [ADR-027](adr/ADR-027-outbox-relay-leader-lock.md) |

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

**해소된 항목**: (1) 도메인 모델과 JPA 엔티티 분리 → **분리한다**, ADR-007로 확정. (2) 고객 주문 API 키 → **생략한다**(무인증), Phase 1 착수 시 확정 — 근거와 그 대가는 §10. Phase 6 이월도 고르지 않았다: 나중에 붙이면 k6·sim-runner·통합 테스트를 소급 수정해야 한다. (3) 이미지 빌드 Jib vs Buildpacks → **Buildpacks**, ADR-013으로 확정. (4) Timefold 실험 포함 여부 → **포함하지 않는다**, [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)로 확정(2026-09-18, Phase 4 마감) — 비교 대상을 외부 솔버에서 **완화 하한**으로 옮겼고, 다시 열 조건 셋을 그 ADR 이 적는다.

Phase 0 마감에서 설계서 내부 모순 두 건도 ADR로 확정했다(원래 `[결정 필요]` 목록에는 없던 항목이다): outbox 발행 측 독약 행 처리 → ADR-015, 레디니스의 Kafka 조건 → ADR-016.

---

## 부록 A. 시드 데이터·시뮬레이션 시나리오

- FC 3개, 캠프 10개(FC당 2·5·3), **권역 91개**(캠프당 6~13), 차량 200대(캠프당 20: 밴 14 · 트럭 6, 그중 냉장 8), 기사 200명.
- **근무조는 둘이다** (2026-09-08, [ADR-030](adr/ADR-030-night-shift-seed.md)): 캠프당 **야간 8대(23:00–08:00)** · **주간 12대(09:00–22:00)**. 냉장·대형·위험물을 두 조에 걸쳐 나눈다 — 야간 냉장 3·트럭 3, 주간 냉장 5·트럭 3. **(2026-09-09 정정: 이 줄은 야간 냉장 5·주간 3 이라고 적고 있었는데, [ADR-031](adr/ADR-031-least-capable-first-tie-break.md) 에서 시드를 되돌릴 때 함께 고치지 못한 자리다. 시드가 기준이다.)** 냉장 비율은 야간 37% · 주간 42% 로 **둘 다** 주문의 냉장 비율(25%) 위에 있다 — 어느 조가 도는 시각에도 cold-chain 이 막다른 길이 아니어야 한다. **이전에는 200대 전부 06:00–22:00 이었다** — 「당일·새벽 배송」을 표방하면서 DAWN 티어의 약속 시간대(§2.2 익일 00:00–07:00)에 일하는 차량이 하나도 없었다. 이 부록의 누락이었다.
- **위험물 허용은 차량의 20%, 그중 절반이 냉장이다** (2026-09-09, [ADR-033](adr/ADR-033-constraint-classes.md)): 캠프당 4대(주간 2 · 야간 2), 그중 냉장 ∧ 위험물 2대로 **조마다 한 대씩**. 이전에는 대수가 이 부록에 적혀 있지 않았고 시드는 캠프당 2대(그중 냉장 1대)였다 — **겹친 제약이 한 대에 몰리면 그 한 대가 고장 나거나 이미 찬 순간 그 수요는 어떤 알고리즘으로도 실을 수 없다.** 벤치마크에서 같은 결함이 `large` 미배정 89건으로 나타났고, 거기서 세운 기준(제약 조합별 수요 ≤ 그 조합 차량 용량의 80%)을 운영 시드로 옮긴 것이 이 대수다.
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
| 새 기술 검토 | ADR-004(**도입하지 않는 결정** + 되돌릴 조건 셋), ADR-010(OSRM) |
| 코드 품질·자동화 | ArchUnit, 커버리지 게이트, CI 스모크, 벤치마크 회귀 |

## 부록 C. 용어집 보충

- **geohash7**: 약 153 m × 153 m 셀. stop 통합·거리 캐시 키로 사용. **geohash5**: 약 4.9 km × 4.9 km, 권역 매핑에 사용.
- **Sweep**: 창고 기준 각도 순으로 고객을 훑으며 용량 한도에서 클러스터를 자르는 고전 VRP 휴리스틱.
- **Clarke-Wright savings**: 두 고객을 한 라우트로 합칠 때 절감되는 거리 `s(i,j)=d(0,i)+d(0,j)-d(i,j)`가 큰 순으로 병합하는 휴리스틱.
- **2-opt / Or-opt**: 라우트 내 구간 뒤집기 / 소구간 이동으로 거리를 줄이는 지역 탐색.
