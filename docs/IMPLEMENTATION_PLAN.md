# IMPLEMENTATION_PLAN.md — Dawnline Phase별 작업 지시서

각 Phase는 Claude Code에 한 번에 넘길 수 있는 작업 단위다. 순서대로 진행하고, **DoD(완료 기준)의 검증 명령을 실제로 실행한 결과**로 완료를 판단한다.
설계 근거는 `docs/DESIGN.md` 해당 절(§)을 참조한다.

Phase 0–3 = MVP(면접 데모 가능). Phase 4, 7 = Staff 레벨 차별화. Phase 5, 6은 시간 부족 시 축소 가능(축소 범위는 각 Phase에 명시).

---

## Phase 0 — 스캐폴딩과 플랫폼 라이브러리

**목표**: 빈 서비스 5개가 Compose에서 뜨고, 공통 라이브러리와 CI·ArchUnit 규칙이 자리 잡는다.

**작업**
1. Gradle 멀티프로젝트: `settings.gradle.kts`, `buildSrc` 컨벤션 플러그인(`dawnline.java-conventions`, `dawnline.spring-service`), `gradle/libs.versions.toml`에 §11 버전 고정. JDK 25 toolchain.
2. `libs/common`: `Ids`(UUIDv7), `GeoPoint`, `Geohash`(encode 5/7, 이웃 계산), `Money`(KRW long), `TimeWindow`, `DomainException`, Problem Details 매핑 기본.
3. `libs/messaging`: `EventEnvelope`, `OutboxEvent` 엔티티 + Flyway 스크립트(`outbox_events`, `processed_events`), `OutboxRelay`(폴링·SKIP LOCKED·배치·메트릭), `IdempotentConsumer`, Kafka 프로듀서/컨슈머 공통 설정(헤더 전파, 에러 핸들러·DLQ·백오프), 계약 검증 유틸(JSON Schema).
4. `libs/observability`: 메트릭 이름 상수, MDC 필터, JSON 로그 설정, OTel 설정.
5. 서비스 5개 골격: 헬스/레디니스, Flyway, 빈 도메인 패키지, ArchUnit 테스트(§13 규칙 5개), 서비스별 DB 사용자.
6. `deploy/compose`: PostgreSQL 18(초기 SQL로 DB 5개·사용자 생성), Kafka 4.3 KRaft 단일 노드 + 토픽 생성 init 컨테이너(§4.1 목록, 파티션 12, DLQ 포함), Redis 8, Prometheus, Grafana(프로비저닝), Tempo, OTel Collector. `Makefile`.
7. `.github/workflows/ci.yml` 골격(빌드·테스트·Compose 스모크 자리).
8. `docs/adr/ADR-001~003, 006, 007` 초안. `README.md` 골격.
9. `contracts/events/`: §4.3의 스키마 4종 + examples.

**DoD**
- `./gradlew build` 성공, ArchUnit 테스트 존재·통과.
- `make up` 후 5개 서비스 `/actuator/health/readiness` 200, Kafka 토픽 목록에 §4.1 전체 존재.
- `libs/messaging` 통합 테스트: outbox INSERT → 릴레이 → Kafka 수신 → 멱등 소비 2회 호출 시 1회만 처리.
- CI 워크플로가 PR에서 녹색.

---

## Phase 1 — order-service (주문 접수)

**작업**
1. 도메인: `Order` 애그리거트, 상태 머신(§5.1), `DeliveryAddress`, `Parcel`, `PromisedWindow`, `TierEligibility`, `Geocoder` 포트(스텁 구현: 우편번호 앞 3자리 → 좌표 테이블 + 지터).
2. 유스케이스: `PlaceOrder`(멱등 흐름 §5.1), `CancelOrder`, `GetOrder`, `ListOrders`(커서).
3. 어댑터: REST(v1, Bean Validation, Problem Details), JPA(Flyway V1: §5.1 DDL), Outbox 발행(`order.placed`, `order.cancelled`), Redis 멱등 키·레이트 리밋(Lua).
4. 리스너: `order.dispatched`, `delivery.status` → 상태 전이(멱등).
5. OpenAPI 생성 → `contracts/openapi/order-service.yaml`.
6. 메트릭: `dawnline_orders_placed_total`, outbox 지표 노출.
6-1. 레이트 리밋(§7.2 `rl:customer:{id}`, Lua 토큰버킷 60/min): `POST /orders` 앞단, 429 + Problem Details + `Retry-After`, Redis 장애 시 fail-open + `bypassed` 메트릭·알림. **§10 무인증 결정의 보상 통제이므로 Phase 1 을 이것 없이 닫지 않는다.**
7. 테스트: 단위(상태 머신 전이표 전체), 통합(멱등 재요청·다른 본문 422·취소 409·outbox 발행), 계약(order.placed 스키마).
8. k6 스크립트 `tools/k6/orders.js`(500 rps 60초).
9. `sim-runner` 최소 버전: 주문 생성기만(`smoke` 시나리오 200건).

**DoD**
- 통합 테스트 통과(Testcontainers PG·Kafka·Redis).
- Redis를 중단한 상태에서도 멱등 POST가 정확히 동작(테스트로 증명).
- k6 결과를 `docs/benchmarks/phase1-orders-k6.md`에 기록(p50/p95/p99, 오류율). 목표 미달이면 원인 분석 포함.
- k6 는 두 스크립트다. `orders.js` 는 `customerId` 를 1만 명 이상으로 분산해 **부하만** 측정하고
  (500 rps ÷ 10,000 = 고객당 0.05 rps 라 레이트 리밋에 걸리지 않는다), `rate-limit.js` 는 고객
  한 명이 5 rps 로 쏴서 60건 이후 429 와 `Retry-After` 가 나오는지를 **동작 검증**한다.
  후자는 부하 리포트가 아니라 통합 테스트의 연장이므로 같은 문서의 별도 절에 적는다.

**Phase 7 로 이월 (조건부)**
- §8.3 의 전역 `Bulkhead`(동시 요청 상한). Phase 1 에서는 고객별 레이트 리밋까지만 한다.
  **조건**: 9단계 k6 에서 HikariCP 풀(인스턴스당 10, §8.2) 포화가 관측되면 Phase 1 안으로 당긴다.
  포화 여부는 `hikaricp_connections_pending` 로 판단하고, k6 리포트에 그 값을 함께 기록한다.
  판정 기준과 기록 자리는 `docs/benchmarks/phase1-orders-k6.md` 6절에 있다 — 기준을 **측정 전에**
  적어 둔 것은, 숫자를 본 뒤에 기준을 만들면 어떤 결과든 설명이 되기 때문이다.

**마감 대조표** (CLAUDE.md 「작업 방식」 — 기억이 아니라 표로 확인한다)

기준일 2026-09-05, `main` = PR #7 머지 시점. 빠진 항목은 **표에 남긴다**.

| # | 작업 | 상태 | 근거 |
|---|---|---|---|
| 1 | 도메인·상태 머신·`TierEligibility`·`Geocoder` 스텁 | ✅ | `OrderTest`·`OrderStatusTest`(36조합 + 축 규칙)·`DeliveryPromiseTest`·`PostalPrefixGeocoderTest` |
| 2 | `PlaceOrder`·`CancelOrder`·`GetOrder`·`ListOrders` | ✅ | `PlaceOrderServiceTest`·`CancelOrderServiceTest`·`OrderQueryServiceTest` |
| 3 | REST·JPA(V1·V2)·Outbox 발행·Redis 멱등 | ✅ | `OrderApiIT`·`OrderPersistenceIT`·`IdempotencyRecordsIT`·`OrderPlacedContractTest`·`OrderCancelledContractTest` |
| 4 | 리스너 `order.dispatched`·`delivery.status` | ✅ | `OrderProgressListenerIT`(8건, 계약 예시를 브로커에 직접 발행) |
| 5 | OpenAPI → `contracts/openapi/order-service.yaml` | ✅ | `OpenApiContractIT` — 문서와 코드의 일치까지 검사 |
| 6 | 메트릭 `dawnline_orders_placed_total`, outbox 지표 | ✅ | `PlaceOrderServiceTest`(접수·재생 카운터), outbox 게이지는 `libs/messaging` 이 등록 |
| 6-1 | 레이트 리밋(Lua 토큰버킷) | ✅ | `RateLimitIT`(실물 Redis 8건)·`RateLimitApiIT`(429 계약 4건) |
| 7 | 테스트: 단위·통합·계약 | ✅ | 단위 770건(9단계의 sim-runner 36건 포함) · 통합 97건. 빈 칸 5개는 8단계에서 채웠다(아래) |
| 8 | k6 `orders.js` + `rate-limit.js` | ⚠️ **스크립트만** | `tools/k6/`. 실측은 아직 — 아래 DoD 참고 |
| 9 | `sim-runner` smoke 200건 | ✅ | `tools/sim-runner` (36건, 라인 97.1%). `make smoke` |

| DoD | 상태 | 근거 |
|---|---|---|
| 통합 테스트 통과(PG·Kafka·Redis) | ✅ | 91건 통과. Redis 컨테이너는 `RateLimitIT`·`RateLimitApiIT` 가 띄운다 |
| Redis 중단 상태에서 멱등 POST | ✅ | `PlaceOrderIT`(유스케이스) + `OrderApiIT.Redis_없이도_멱등_POST_가_HTTP_계층에서_성립한다`(HTTP). 후자는 먼저 `tryLock` 이 `UNAVAILABLE` 인지 확인해 **전제를 테스트가 스스로 말한다** — 확인하지 않으면 나중에 누가 Redis 를 붙였을 때 전제가 조용히 사라진다 |
| k6 결과 기록 | ❌ **미측정** | 스크립트·문서 골격은 `main` 에 있다(`docs/benchmarks/phase1-orders-k6.md`). 결과 표는 **빈 칸인 채로 커밋했다** — 채워지지 않은 칸이 있어야 Phase 1 이 안 닫혔다는 것이 표에 보인다. 실측은 스택을 띄울 수 있는 환경에서 별도로 한다 |
| §8.3 Bulkhead 를 Phase 1 으로 당길지 판정 | ❌ **미판정** | 판정 기준과 자리는 벤치마크 문서 6절에 있다. `hikaricp_connections_pending` 실측이 있어야 판정된다 |

**Phase 1 은 아직 닫히지 않았다** (2026-09-05)

위 DoD 의 ❌ 두 줄(k6 결과 기록 · §8.3 Bulkhead 판정)은 `make up` 이 가능한 환경에서의 실측
**한 번**으로 함께 채워진다. 사용자 결정으로 그 실측을 뒤로 미루고 **Phase 2 를 먼저 진행**한다.

두 줄은 채워질 때까지 표에 ❌ 로 남는다. Phase 1 마감 보고는 그때 낸다 — 미룬 것을 "완료" 로
바꿔 적지 않는다. 미루는 것과 끝난 것은 다르고, 표가 그 둘을 구별하지 못하면 표를 만든 이유가
없다.

**빈 칸 5개 — 8단계에서 모두 채웠다**

| 빈 칸 | 왜 필요했나 | 채운 곳 |
|---|---|---|
| HTTP 계층의 Redis 중단 멱등 증명 | 이미 그 조건에서 돌고 있었으나 어설션이 없어 증거가 아니었다 | `OrderApiIT.Redis_없이도_멱등_POST_가_HTTP_계층에서_성립한다` |
| `DISPATCHED` 이후 취소 409 | §5.1 API 표가 직접 든 경우인데 HTTP 계층에 없었다 | `OrderApiIT.배송이_시작된_뒤에는_취소가_409_다` |
| 커서 빈 결과 | 주문이 없는 고객이 첫 화면에서 만나는 경로다 | `OrderApiIT.주문이_없는_고객의_목록은_빈_페이지다` |
| 레이트 리밋 `bypassed` 가 메트릭까지 | §9.4 알림이 이 값에 걸린다. 값이 안 나오면 알림도 안 온다 | `OrderApiIT.Redis_가_없으면_레이트_리밋이_bypassed_로_기록된다` |
| `order.placed` 가 실제 브로커에 도착 | 릴레이는 `libs/messaging` IT 가, 페이로드는 단위 계약 테스트가 본다. **그 둘 사이** — order-service 의 발행이 브로커까지 가서 <em>봉투까지</em> 계약을 지키는지 — 는 아무도 보지 않았다. 다른 IT 는 모두 릴레이를 꺼 두었기 때문이다 | `OrderPublishIT`(이 클래스만 릴레이를 켠다) |

이 마지막 항목은 **대조표를 만들지 않았으면 못 봤다.** 두 테스트가 각자 자기 절반을 보고 있어서
어느 쪽에서도 빠진 것으로 보이지 않았다.

---

## Phase 2 — fulfillment-service (FC 선택·웨이브·컷오프)

**작업**
1. 시드: FC 3, 캠프 10, **권역 91**(geohash5), 재고 스텁, 차량·기사는 Phase 3에서.
   시드는 **Flyway `R__seed_*.sql`** 로 통일한다(2026-09-05 확정 — `sim-runner` 는 §5.6 대로 REST
   전용으로 남아 남의 서비스 DB 에 쓰지 않고, Testcontainers 통합 테스트가 시드를 자동으로 얻는다).
   권역이 60이 아니라 91인 이유와 셀→캠프 배정 규칙은 [ADR-021](adr/ADR-021-zone-seed-derived-from-geocoder.md).
   `contracts/seed/order-service-geohash5.txt` 를 생성물로 커밋하고 양쪽 서비스가 각자 검사한다.
2. Redis GEO 적재, `GEOSEARCH` 기반 최근접 FC 선택, geohash5 → zone 캐시. **그리고 각각의 DB 폴백**(불변규칙 7).
   - **레디니스에 넣지 않는다.** 적재는 best-effort + 주기 재시도이고, 상태는
     `dawnline_geo_index_loaded{index}` 게이지(0/1), 폴백 사용은
     `dawnline_geo_lookups_total{index,outcome="bypassed"}` 로 본다
     ([ADR-016](adr/ADR-016-readiness-excludes-kafka.md) 후속 정정, §8.6).
     폴백이 있는 의존성을 레디니스에 넣으면 Redis 장애가 곧 서비스 차단이 되어 폴백을 만든
     이유가 사라진다.
   - **폴백은 같은 답을 내야 한다.** ADR-020 의 "멱등 소비자가 막는 것은 중복이지 다른 결과가
     아니다" 가 여기서 그대로 적용된다 — Redis 가 죽었다는 이유로 같은 주문이 다른 FC 를 받으면
     안 된다. 그러려면 **순위 결정과 동률 처리(FC code 순)는 순수 판정 함수 안**에 두고, Redis
     어댑터와 DB 어댑터는 **거리만** 넘긴다(작업 3 의 `FcSelection` 이 이미 그 모양이다).
   - **동등성 테스트**: 시드 전체(캠프 10 × FC 3)에서 두 경로가 **같은 순위**를 내는지 본다.
     Redis GEO 의 거리 계산과 하버사인은 미세하게 다를 수 있으므로 **순위는 정확히, 거리는 허용
     오차로** 비교한다.
   - **폴백 강제 IT**: `PlaceOrderIT` 처럼 죽은 Redis 주소로 컨텍스트를 띄워 GEO 없이도 FC 선택이
     성립함을 보인다(§13 매핑표 불변규칙 7 의 강제 수단을 fulfillment 쪽으로 넓힌다).
3. FC 선택 규칙(§5.2 1~6단계), `UNSERVICEABLE` 경로.
   **순수 함수로 만든다**: `(주문, 캠프, FC 목록, Clock) → 결과(FC | UNSERVICEABLE 사유 | fallback 사유)`.
   Redis GEO·DB 는 어댑터가 **FC 목록과 거리를 준비해 넘기고**, 판정 자체는 Spring 없이 단위
   테스트된다(불변규칙 5). 시드에 일부러 넣은 세 결손(`tier`/`cold`/`inventory`)이 각각 fallback
   사유로 나오는 테스트가 **그 함수의 명세**다.
   `STALE_PLACED`(24h)와 grace(90초)는 둘 다 주입된 `Clock` 으로 판정하고, 시간 경계 테스트는
   **경계 양쪽 1초씩** 둔다(불변규칙 12).
4. **애그리거트 둘**: `Wave`(상태 머신 `OPEN→CLOSING→CLOSED→PLANNED/PLAN_FAILED`)와
   `FulfillmentOrder`(`PLANNED | UNSERVICEABLE | CANCELLED`, [ADR-022](adr/ADR-022-fulfillment-order-aggregate.md)).
   `FulfillmentOrder` 의 전이는 order-service 와 같은 **축 규칙**을 쓴다(`4a44df4`, ADR-017) —
   진행 축에서 앞으로 가는 전이는 건너뛰어도 허용하고, 뒤로 가는 전이는 무시하고 stale 로 센다.
   **취소 선착이 그 축의 한 사례라는 것을 전이표 주석에 남긴다**: `CANCELLED` 는 축 밖의 종결
   상태이고, 그 뒤에 오는 `order.placed` 는 "역행" 이라 무시된다 — 별도 마커가 필요 없는 이유가
   바로 이 규칙이다.
   편입 로직(UNIQUE + `FOR UPDATE` 짧은 트랜잭션), 컷오프 스케줄러(30초, Redis 락, Lua 언락),
   `CLOSING→CLOSED` 전이와 `wave.closed` outbox.
4-3. **계획 결과 계약과 웨이브 축** ([ADR-024](adr/ADR-024-plan-completed-event.md)):
   `contracts/events/plan.completed.v1.schema.json`·`plan.failed.v1.schema.json` 을 **소비자 주도**로
   정의한다(Phase 1 의 `order.dispatched`·`delivery.status` 와 같은 방식). 웨이브의 계획 완료는
   `route.assigned` 가 아니라 `plan.completed` 가 알린다 — 라우트 단위 이벤트는 "언제 웨이브가
   `PLANNED` 인가" 에 답할 수 없고, 그 전이가 없으면 4-2 의 정리 배치가 `PLANNED` 주문 행을 영원히
   지우지 못한다. `PLAN_FAILED → PLANNED`(운영자 재실행)를 열고, 마지막 두 전이에만 축 규칙을
   적용한다 — 두 이벤트가 **다른 토픽**이라 재실행 시 순서가 뒤바뀌면 라우트가 나간 웨이브가
   실패로 표시되기 때문이다.
4-1. **`V2__fulfillment_orders.sql`**: `fulfillment_orders` 생성 + `wave_orders` 드롭 + 부분 인덱스
   `ix_fulfillment_orders_wave`. 인덱스는 불변규칙 11 대로 EXPLAIN 을 PR 에 첨부한다.
   **EXPLAIN 은 CI 에서 돌려 옮겨도 되되, 리포트에 환경을 적는다** — CI 러너 사양(vCPU·메모리),
   PostgreSQL 버전, 측정에 쓴 행 수. 환경 없는 수치는 나중에 비교 대상이 되지 못한다.
   V1 은 이미 `main` 에 있으므로 고치지 않는다(불변규칙 13, 예외 없음) — 방금 만든 빈 테이블을
   지우는 마이그레이션이 이력에 남고, 그것이 정직한 이력이다.
4-2. **정리 배치** ([ADR-023](adr/ADR-023-fulfillment-retention.md)): `fulfillment_orders` 30일
   (`updated_at` 기준, **종결 상태만** — `CANCELLED`·`UNSERVICEABLE`·소속 웨이브가
   `PLANNED`/`PLAN_FAILED` 인 `PLANNED`), `waves` 90일. `ProcessedEventCleaner` 패턴으로 일 1회,
   `ctid` 경유 `LIMIT` 배치 반복, **배치마다 커밋**(ADR-019 의 측정: 0.47초 vs 11.29초).
   **파티셔닝하지 않는다** — 파티션 키가 PK 에 들어가면 ADR-022 가 확보한 `order_id` 단독 PK
   보장이 약해진다.

   인덱스 판단을 표에 남긴다(불변규칙 11 — 넣는 것도, **넣지 않는 것도** 기록한다).

   | 표 | 30·90일치 행 수 | 인덱스 | 근거 |
   |---|---|---|---|
   | `fulfillment_orders` | 피크 150,000/일 × 30일 = **450만** | `updated_at` 추가 (100 MB) | 삭제 조건이 `updated_at < now() - 30d` 범위 스캔이다. PK 가 `order_id` 라 이 범위를 돕지 못한다. 하루치 정리 68초 → 0.24초 |
   | `fulfillment_orders` | 〃 | `wave_id` **전체** 추가 (32 MB) | ADR-022 의 부분 인덱스를 **측정이 뒤집었다** — 부분 조건이 거르는 행이 2% 뿐이고, 부분 인덱스는 FK 검사에 쓰이지 못해 `waves` 삭제가 웨이브당 전수 스캔이 된다(40건에 6.7초 → 0.57 ms) |
   | `waves` | 하루 40행 × 90일 = **약 4,000** | **넣지 않는다** | 후보를 고르는 순차 스캔이 3,600행에 0.42 ms · 50버퍼다. 재검토가 필요한 규모는 백만 행대(캠프 2,500개 수준)이며, 행 수를 함께 적는 이유가 그 재검토 지점을 만들기 위해서다 |

   측정 결과는 [`docs/benchmarks/phase2-fulfillment-orders-indexes.md`](benchmarks/phase2-fulfillment-orders-indexes.md)
   에 환경(호스트 사양·PG 버전·행 수)과 함께 남긴다.
5. 리스너: `order.placed` → 계획·편입·`fulfillment.planned` 발행. `order.cancelled` → 주문 상태만 `CANCELLED`
   (**웨이브 카운트는 건드리지 않는다** — [ADR-025](adr/ADR-025-wave-admission-share-lock.md) 이후
   `order_count` 는 마감 시 집계라 취소가 자동으로 빠진다).
   편입은 웨이브 행 **`SELECT … FOR SHARE`** 로 상태를 확인한 뒤 `fulfillment_orders` INSERT,
   마감은 **`FOR UPDATE`** (ADR-025). 다음 컷오프가 필요하면 `libs/common` 의 `CutoffSchedule` 을
   부른다 — 표를 여기에 다시 적지 않는다(ADR-020 후속 정정 2).

   **마감 대조표에 반드시 열로 들어갈 것** (기억이 아니라 표로 확인한다):

   | 항목 | 어떻게 증명하나 |
   |---|---|
   | `fulfillment.planned` 브로커 도착 | outbox → 릴레이 → **실제 브로커** → 계약 검증 IT (Phase 1 8단계의 `OrderPublishIT` 와 같은 형태). "outbox 에 들어갔다" 까지만 보면 릴레이·봉투 조립이 검증되지 않는다 |
   | `wave.closed` 브로커 도착 | 〃. 키가 `campId` 인 것과 `orderCount` 가 마감 시 집계값인 것까지 확인 |
   | `plan.completed` 소비 | 예시 이벤트 발행 → 웨이브 `CLOSED → PLANNED` ([ADR-024](adr/ADR-024-plan-completed-event.md)) |
   | `plan.failed` 소비 | 〃 `CLOSED → PLAN_FAILED`, 그리고 재실행 경로 `PLAN_FAILED → PLANNED` |
   | 이미 `PLANNED` 인 웨이브의 늦은 `plan.failed` | 무시 + `dawnline_event_rejected_total{reason="wave_already_planned"}` (축 규칙, ADR-024 결정 4) |
   | 편입 동시성 | 같은 웨이브에 동시 편입이 서로 막지 않고, 마감이 그것을 기다린 뒤 `CLOSING` 으로 간다 (ADR-025) |
   마감은 `cutoffAt + grace`(기본 90초)이고, grace 를 넘긴 주문은 다음 웨이브 + `promiseRevised: true` ([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md)).
   단 **`cutoffAt < now − 24h`(설정값)이면 다음 웨이브가 아니라 `UNSERVICEABLE`(`STALE_PLACED`)** 이다
   (ADR-020 후속 정정). 상한이 없으면 20일 묵은 `order.placed` 가 DLQ replay 로 들어와 오늘 날짜의
   새 배송 약속을 만든다 — 유령 배송이다.
5-2. **계획 결과 리스너 둘**: `plan.completed` → `Wave.markPlanned()`, `plan.failed` →
   `Wave.markPlanFailed()`. 발행자는 Phase 3 에 생기지만 계약이 있으므로 리스너와 통합 테스트는
   예시 이벤트를 Testcontainers Kafka 에 직접 발행해 **지금 완결된다**(계약 README 1절).
   그러지 않으면 두 전이가 Phase 3 까지 한 번도 검증되지 않고, 4-2 정리 배치도 그때 처음 돌아 본다.
   이미 `PLANNED` 인 웨이브에 온 `plan.failed` 는 `WaveStatus.hasProgressedPast` 로 무시하고
   `dawnline_event_rejected_total{reason="wave_already_planned"}` 를 올린다(ADR-024 결정 4).
5-1. **order-service 쪽 대응** — `fulfillment.planned` 리스너: `outcome=UNSERVICEABLE` → 주문 `FAILED` + `reason` 기록(§5.2 6단계),
   `promiseRevised: true` → `Order.revisePromise(window, at)` 로 `promised_start/end` 갱신(세터가 아니라 메서드, 불변규칙 6).
   **원래 작업 목록에 없던 항목이다.** §4.1 은 `fulfillment.planned` 의 소비자로 order 를 적었고 §5.2 도
   order-service 가 사유를 기록한다고 적었는데, Phase 2 작업 목록에는 order-service 쪽 일이 한 줄도
   없었다 — 넣지 않으면 마감 대조표에서 "누가 UNSERVICEABLE 을 FAILED 로 바꾸나" 가 빈 칸으로 남는다.
6. 순서 역전 처리: **별도 마커를 두지 않는다.** `order.cancelled` 가 먼저 오면
   `fulfillment_orders` 에 `status=CANCELLED`·`placed_event_id=NULL` 행이 생기고, 뒤에 온
   `order.placed` 는 그 행을 보고 무시하며 `dawnline_event_rejected_total{reason="cancelled_before_placed"}`
   를 올린다. 취소 후착의 두 분기(웨이브 `OPEN` 이면 카운트 감소, `CLOSING/CLOSED` 이후면 상태만)는
   [ADR-022](adr/ADR-022-fulfillment-order-aggregate.md) 의 표를 따른다.
6-1. **메트릭**: `dawnline_wave_orders{camp,tier}`(게이지), `dawnline_promise_revised_total{camp,tier}`(카운터),
   `dawnline_fc_fallback_total{camp,reason}`(카운터, `reason`=tier/cold/inventory).
   앞의 둘은 §9.1 에 예약되어 있었으나 작업 목록에는 없었다. `promise_revised` 는 ADR-020 의 개정이
   실제로 일어났는지를 보는 **유일한** 값이고, 이것이 없으면 §8.1 의 정시율 두 기준을 나중에 맞출 수 없다.
   `fc_fallback` 은 [ADR-021](adr/ADR-021-zone-seed-derived-from-geocoder.md) 이 §5.2 5단계를 확정하며
   함께 정한 것으로, 대체 FC 선택이 조용히 일어나지 않게 한다 — 계속 오르는 캠프는 홈 FC 배정이
   잘못됐거나 그 FC 의 역량이 부족한 것이고, 그것이 §5.2 FC 선택 규칙이 드러내려던 사실이다.

   `dawnline_event_rejected_total` 의 `reason` 은 이 Phase 에서 셋이 된다 —
   `cancelled_before_placed`(6), `wave_already_planned`(5-2), 그리고 order-service 가 이미 쓰는 것.
   **§9.1 이 적어 둔 라벨 확장 트리거가 여기서 켜진다**: "거부하는 소비자가 둘 이상 되면
   `consumer`·`eventType` 을 붙인다". order 와 fulfillment 둘이 되므로 이 Phase 에서 붙이고,
   Prometheus 는 같은 이름의 미터가 **같은 라벨 키 집합**을 갖기를 요구하므로(ADR-022 에서 jar 로
   확인) 양쪽을 함께 고친다. 한쪽만 붙이면 다른 쪽 미터 등록이 실패한다.
7. 테스트: 스케줄러 인스턴스 2개 동시 실행 시 `wave.closed` 정확히 1회(통합), 컷오프 이후 주문이 다음 웨이브로 가는지, GEO 폴백(Redis 중단).

**DoD**
- `make demo` 실행 시 주문 200건이 자동으로 웨이브에 편입되고, 컷오프(테스트용 짧은 컷오프 설정)에 `wave.closed`가 캠프별 1회 발행됨을 Kafka 소비 로그·DB로 확인.
- 이중 마감 없음 테스트 통과.
- 순서 역전 두 방향(취소 선착·후착)과 웨이브 상태별 분기가 통합 테스트로 증명된다(ADR-022 표 전체).
- 24시간 넘은 `order.placed` 가 `UNSERVICEABLE`(`STALE_PLACED`)로 종결되고 다음 웨이브에 들어가지 않는다.
- 정리 배치가 종결 상태만 지우고 진행 중 주문을 건드리지 않는다(ADR-023).
- `plan.completed`/`plan.failed` 예시 이벤트로 `CLOSED → PLANNED`·`CLOSED → PLAN_FAILED`·
  `PLAN_FAILED → PLANNED`(재실행)가 통합 테스트로 증명되고, 이미 `PLANNED` 인 웨이브에 온
  `plan.failed` 가 무시되고 카운트된다(ADR-024).
- **게이트 — §8.3 Bulkhead 판정이 설계서에 기록되어 있지 않으면 Phase 2 를 닫지 않는다.**
  기록에는 Phase 1 k6 의 `POST /orders` p99, outbox 지연 p95, `hikaricp_connections_pending`
  최댓값, 그리고 판정(Phase 1 으로 당김 / Phase 7 유지)이 함께 들어간다. 자리는 DESIGN §8.3 의
  「Bulkhead 판정 기록」 표이고 원자료는 `docs/benchmarks/phase1-orders-k6.md` 다.
  **두 번 요청되고도 오지 않은 항목은 기억이 아니라 게이트로 처리한다** — Phase 1 의 레이트
  리밋이 그렇게 빠질 뻔했고, 이 항목은 이미 두 번 미뤄졌다.
- `UNSERVICEABLE` 이 **시드 부족 때문에** 나오지 않는다: 시드된 `zones` 가 `contracts/seed/order-service-geohash5.txt` 의 91개 셀을 전부 덮는지 양쪽 서비스가 각자 검사(ADR-021).

**Phase 7 로 이월 (조건부)**
- **lag-aware grace** — 웨이브 마감 grace 를 고정 90초가 아니라 컨슈머 랙에 연동한다.
  Phase 2 에서는 고정 90초까지만 한다([ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 결정 5).
  **조건**: Phase 7 `peak-day` 시뮬레이션에서 `dawnline_promise_revised_total` 이 **컷오프 직후에
  뭉쳐서** 튀면 lag-aware 로 간다. 고르게 흩어져 있거나 거의 없으면 고정 90초를 유지한다.
  판정 결과를 `docs/benchmarks/<date>-peak.md` 에 기록한다.
  판정 조건을 지금 적어 두는 이유는 Phase 1 의 원인 판정표와 같다 — 수치를 본 뒤에 기준을 만들면
  어떤 결과든 설명이 된다.

---

## Phase 3 — dispatch-service 코어 (룰 엔진 + 기본 최적화)

**작업**
1. `domain.optimizer` (순수 Java): §6.2 모델, `DistanceProvider`(하버사인), `StopMerger`, `SweepClusterer`, `GreedyAssigner`, `NearestNeighborSequencer`, `PlanValidator`, `CostModel`.
2. 룰 엔진: `DispatchRule` sealed 계층, §6.3 카탈로그 10종 평가기, `RuleSet` 로딩(DB → 캠프 오버라이드 병합 → 캐시), `Explanation` 수집.
3. 전략: `baseline-nn`, `sweep-greedy-nn`. `DispatchStrategy` 인터페이스·레지스트리.
4. 애플리케이션: `RunPlan` 유스케이스(Plan 상태 머신 §5.3, wave_id UNIQUE 멱등, PLANNING 정체 회수 스케줄러), 후보 적재(`fulfillment.planned` 리스너), `wave.closed` 리스너 → 계획 실행 → `route.assigned`(라우트당) + `order.dispatched`(주문당) outbox.
5. REST: plans/routes/rules/vehicles/drivers(§5.3 표). 룰 수정 시 `rule_version` 증가·이력.
6. Flyway V1: §5.3 DDL. 시드: 차량 200·기사 200·기본 룰셋 JSON.
7. 메트릭: `dawnline_plan_*`.
8. `tools/benchmark`: 데이터셋 생성기(seed), `small/medium/large` 생성, 전략 실행·비교, Markdown 리포트 출력. CI에 `small` 회귀 체크 연결.
9. 테스트: 룰 평가기 단위(각 룰 위반/통과), 하드 룰 위반 라우트가 최종 산출에 없음(PlanValidator), seed 고정 결정론, 5,000 주문 통합 계획(시간 측정), wave.closed 중복 도착 멱등.
9-1. **`plan.completed.v1` 발행** ([ADR-024](adr/ADR-024-plan-completed-event.md) — 2026-09-05 결정,
   Phase 2 에서 발견한 §4.1↔§5.2 어긋남의 답이다). Plan 이 `PUBLISHED` 에 도달할 때
   `route.assigned`·`order.dispatched` 와 **같은 outbox 트랜잭션**에 넣는다. 나눠 넣으면 "완료라는데
   라우트가 없다" 가 생긴다. 계약은 소비자인 fulfillment 가 Phase 2 에 이미 정의해 두었으므로
   (`contracts/events/plan.completed.v1.schema.json`) 여기서는 **만족시키기만** 한다 — 자기에게
   필요한 필드는 같은 major 안에서 추가만(§4.7).
   `plan.failed` 도 같은 계약이 이미 있다. 부분 재계획(§6.8)은 `plan.completed` 를 다시 내지 않는다.
9-2. **[결정 필요] dispatch 는 `order.cancelled` 를 어떻게 처리하나** — §4.1 은 dispatch 를
   `order.cancelled` 소비자로 적었지만 **무엇을 하는지가 없다.** 이것은 ADR-017 후속 정정이
   드러낸 구멍이다: 취소는 `PLANNED` 에서 허용되고 `PLANNED` 는 웨이브 마감 뒤에도 유지되므로,
   계획 발행과 order-service 의 `order.dispatched` 소비 사이에 **설계된 경합 창**이 있다.
   order-service 가 그것을 무시 + 메트릭으로 남기는 것은 옳지만 그 메트릭은 창의 *크기*를 잴 뿐이고,
   **창을 없애는 일은 dispatch 가 소유한다.**
   방향(세 갈래): ① 후보가 아직 미계획이면 제거 ② 발행된 라우트의 **미출발** stop 이면 stop 취소 +
   `revision` 증가 ③ 출발 뒤면 stop 을 `CANCELLED` 로 표시해 기사가 건너뛰게 하고 `revision` 발행.
   세 갈래가 라우트·배송·주문 세 도메인에 걸쳐 있어 **별도 ADR 감**이다.

10. **릴레이 리더 락 + ADR**: 서비스당 릴레이 단일 활성을 보장한다(Redis `SET NX` + 주기 갱신, 락 상실 시 발행 중단). 스케일아웃으로 인스턴스가 2개 이상이 되기 **전에** 들어가야 한다 — 그 전까지는 인스턴스 1개라는 사실이 §4.4의 전제를 충족시키고 있을 뿐이다.

**DoD**
- `make demo`: 주문 → 웨이브 마감 → 라우트 생성 → `GET /api/v1/plans/{id}`에서 비용·미배정·설명 조회.
- `large` 데이터셋 계획이 완료되고 `docs/benchmarks/phase3-baseline.md`에 baseline-nn vs sweep-greedy-nn 비교표 기록.
- 냉장 주문이 냉장 차량에만 배정됨을 설명(explanation) 조회로 확인.

---

## Phase 4 — 최적화 고도화·열화 모드·벤치마크 리포트 (Staff 차별화)

**작업**
1. `LocalSearchImprover`: 2-opt, Or-opt, inter-route relocate/swap, 시간 예산(`PlanningBudget`), 개선 폭 종료 조건.
2. `savings-cw+ls` 전략.
3. 클러스터 병렬 처리(ForkJoin), Kafka/I/O는 가상 스레드(`spring.threads.virtual.enabled=true`) — 성능 전후 측정.
4. FAST 모드 자동 전환(§6.7 조건), `dawnline_plan_degraded_total`, 수동 재계획 API의 `mode` 파라미터.
5. 벤치마크: 4개 데이터셋 × 3~4 전략 × 5회, 중앙값·p95, `docs/benchmarks/<date>-strategies.md`. README에 표 링크.
6. (선택) `timefold` 전략 실험 → ADR-004 결론에 수치 반영. 기본 경로에 포함하지 않는다.
7. ADR-004, 008 확정.

**DoD**
- `large`에서 `sweep-greedy-nn+ls`가 `baseline-nn` 대비 총비용 ≥ 15% 절감(미달 시 튜닝 기록과 실제 수치 보고).
- 5,000 주문 계획 p95 ≤ 30초(미달 시 프로파일링 결과·병목 문서화).
- 계획 시간 예산을 5초로 줄였을 때 FAST 모드로 전환되고 결과가 여전히 하드 룰을 만족.

---

## Phase 5 — tracking-service + 기사 시뮬레이션 + 재계획

**작업**
1. `Shipment` 상태 머신, `route.assigned` 소비(revision 비교), 스캔 이벤트 API, ETA 갱신, at-risk 규칙·쿨다운, `delivery.status`/`delivery.at-risk` 발행. Flyway(§5.4, 일 파티션 생성 스케줄러).
2. `sim-runner` 기사 시뮬레이터: `route.assigned` 구독 → stop 순회(이동 시간 = 계획 시간 × (1 + 지연 확률·크기)), 실패 확률, 위치 보고.
3. dispatch 재계획(§6.8): `delivery.at-risk` 리스너, 미완료 stop 부분 재계획, `revision` 증가 발행, 쿨다운.
4. 테스트: 역행 스캔 거부, at-risk 1회 발행(쿨다운), 재계획 후 tracking이 새 revision만 반영.

**축소안**: 재계획(3번)을 "운영자 수동 재배정 API"로 대체.

**DoD**
- `late-injection` 시나리오에서 at-risk → 재계획 → revision 반영이 로그·DB로 확인되고, 정시율이 `rm_kpi`/메트릭에 집계됨.

---

## Phase 6 — 백오피스 (ops-api + ops-web)

**작업**
1. ops-api: 전 토픽 프로젝션(§5.5 rm_* 테이블), KPI 시간 버킷 집계, JWT·역할, 커맨드 엔드포인트(웨이브 조기 마감·재계획·stop 재배정·주문 취소·DLQ replay) → 코어 서비스 REST 위임 + `audit_logs`.
2. 코어 서비스에 필요한 운영 엔드포인트 추가(fulfillment: 웨이브 조기 마감; dispatch: 재계획·재배정은 Phase 3/5에서 존재).
3. ops-web: 캠프 대시보드, 웨이브/계획 상세(설명 조회 포함), 라우트 지도(Leaflet, 폴리라인·상태 색), 룰 편집.
4. 테스트: 프로젝션 멱등(같은 이벤트 2회), 권한(viewer가 커맨드 403), 커맨드 감사 기록.

**축소안**: ops-web은 대시보드 + 라우트 지도 2화면만. 룰 편집은 Swagger로 대체.

**DoD**
- 운영자가 UI에서 웨이브를 조기 마감하고 계획 결과·라우트 지도를 보며, 특정 stop을 다른 라우트로 옮기는 흐름이 동작.

---

## Phase 7 — 신뢰성·관측성·문서 마감 (Staff 차별화)

**작업**
1. Grafana 대시보드 4종 JSON, Prometheus 알림 규칙(§9.4) 커밋.
2. 트레이싱 검증: 주문 1건 traceId로 4개 서비스 span이 Tempo에서 연결됨(스크린샷 README).
3. 카오스 스크립트: `make chaos-kafka`, `make chaos-redis`, `make chaos-kill dispatch`. 각 실행 후 검증 SQL(주문 수 = 후보 수 + 취소 수, 라우트 stop 주문 중복 0, processed_events 중복 0)을 자동 실행.
4. 피크 시나리오 `peak-day` 실행·측정: 주문 API p99, outbox 지연, 소비자 랙, 계획 시간, FAST 전환 횟수 → `docs/benchmarks/<date>-peak.md`.
   **`dawnline_promise_revised_total` 을 시간축으로 함께 기록하고 lag-aware grace 판정을 내린다**
   (Phase 2 「Phase 7 로 이월 (조건부)」, [ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 결정 5).
   컷오프 직후에 뭉쳐서 튀면 grace 를 컨슈머 랙에 연동하고, 흩어져 있으면 고정 90초를 유지한다.
5. 런북 RB-01~06, `docs/postmortems/2026-xx-peak-simulation.md`(가상 장애: 컷오프 시 계획 지연 → FAST 전환 → 원인·재발 방지, 실제 측정치 기반).
6. ADR 전체 확정(001–012), README 완성(아키텍처 그림, 데모 GIF, 벤치마크 표, 실행 방법, 면접 스토리 링크).
7. release.yml(GHCR 푸시, SBOM). (선택) `deploy/k8s` 매니페스트 + kind 스모크.

**DoD**
- 카오스 3종 검증 SQL 모두 0건 위반.
- 피크 리포트에 §8.1 SLO 대비 실측 표가 있고, 미달 항목마다 원인·개선안이 적혀 있다.
- README만 읽고 10분 안에 `make up && make demo`로 데모를 재현할 수 있다(사용자 직접 검증).

---

## 완료 보고 템플릿 (매 Phase)

```
## Phase N 완료 보고
1. 구현 요약 (모듈/파일 단위)
2. 실행한 검증 명령과 실제 출력 요약 (빌드, 테스트 수, 벤치마크 수치)
3. 설계서 대비 변경점 / 새 ADR / [결정 필요] 답변 반영 내역
4. 알려진 제약·미해결
5. 다음 Phase 제안 순서
```
