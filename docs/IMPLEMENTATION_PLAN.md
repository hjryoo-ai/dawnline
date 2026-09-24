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
| 8 | k6 `orders.js` + `rate-limit.js` | ✅ **닫힘 — 같은 Phase 의 DoD**(이 표를 쓴 시점에는 ⚠️ 스크립트만) | `tools/k6/`. 실측은 아래 DoD 셋째 줄 — `f7c860d`(2026-09-05). **(2026-09-25, 7-0 대조 검사가 잡았다)** 같은 표 안에서 닫혔는데 이 행만 갱신되지 않았다 |
| 9 | `sim-runner` smoke 200건 | ✅ | `tools/sim-runner` (36건, 라인 97.1%). `make smoke` |

| DoD | 상태 | 근거 |
|---|---|---|
| 통합 테스트 통과(PG·Kafka·Redis) | ✅ | 91건 통과. Redis 컨테이너는 `RateLimitIT`·`RateLimitApiIT` 가 띄운다 |
| Redis 중단 상태에서 멱등 POST | ✅ | `PlaceOrderIT`(유스케이스) + `OrderApiIT.Redis_없이도_멱등_POST_가_HTTP_계층에서_성립한다`(HTTP). 후자는 먼저 `tryLock` 이 `UNAVAILABLE` 인지 확인해 **전제를 테스트가 스스로 말한다** — 확인하지 않으면 나중에 누가 Redis 를 붙였을 때 전제가 조용히 사라진다 |
| k6 결과 기록 | ✅ | 2026-09-05 실측, 커밋 `f7c860d`. `docs/benchmarks/phase1-orders-k6.md` 3·5·7·8·9절. **웜 p99 4.8~48 ms**(목표 200 ms), 5xx 0, 레이트 리밋 계약 통과(100/101, 체크 100%), smoke 200/200 |
| §8.3 Bulkhead 를 Phase 1 으로 당길지 판정 | ✅ **Phase 7 유지** | §8.3 「Bulkhead 판정 기록」 표에 기록. 조건(`pending`>0)은 **콜드에서 켜졌으나**(191) 원인이 풀 분리로 완화되는 종류가 아니었다 — 0.75 CPU → SerialGC → full GC 166회·17.11초 → 커넥션 점유 3.07초. 웜에서는 `pending=0` |

**Phase 1 마감** (2026-09-05)

두 줄이 실측 한 번으로 함께 채워졌다. **미달은 콜드 스타트 창 하나뿐이고 재현된다** — 그것을
"통과" 로 적지 않고 별도 항목으로 열었다(아래).

측정이 남긴 것 셋.

1. **콜드/웜을 갈라 적는다.** 웜 p99 는 목표의 1/4~1/40 이고 콜드는 10~20배다. 한 값으로
   합치면 둘 다 거짓이 된다. 회귀 감시선은 웜 기준으로 두고 콜드는 따로 추적한다.
2. **`bypassed` 카운터가 잘못된 결론을 막았다.** 첫 레이트 리밋 측정이 임계를 넘겼는데, 원인은
   리밋이 아니라 fail-open 이었다(그 회차 `bypassed` 28,800). 그 칸이 없었다면 "레이트 리밋이
   설계대로 동작하지 않는다" 로 적혔을 것이다. §7.2 가 "fail-open 은 반드시 관측된다" 고 적어
   둔 값이 실제로 그 일을 했다.
3. **콜드 스타트를 Phase 7 로 연다.** 0.75 CPU 에서 JVM 이 SerialGC 를 고르는 것이 사슬의
   시작이다. 후보 대응(CPU 상향·AppCDS·워밍업·레디니스 확장)은 벤치마크 문서 6절에 적었고,
   **지금 고르지 않는다** — 롤링 배포 창의 문제라 §8.6 과 함께 봐야 한다.

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

**마감 대조표** (CLAUDE.md 「작업 방식」 — 기억이 아니라 표로 확인한다)

기준일 2026-09-05, `main` = PR #18 머지 시점. 단위 990건 · 통합 147건 · 실패 0.
커버리지 fulfillment 72.6% / order 91.2% / messaging 85.6% / common 96.2%.
빠진 항목은 **표에 남긴다**.

| # | 작업 | 상태 | 근거 |
|---|---|---|---|
| 1 | 시드 FC 3 · 캠프 10 · 권역 91 · 재고 스텁 (Flyway `R__`) | ✅ | `608a01c`(V1 + `R__seed_fulfillment`) · `9753ca8`(`contracts/seed/order-service-geohash5.txt`) · ADR-021. `ZoneSeedCoverageIT` 9건 — 91셀 전수 덮기, 규모 대조, 재실행 멱등, **홈 FC 50 km 이내**(`63f1e1b`) |
| 2 | Redis GEO 적재 · `GEOSEARCH` · geohash5→zone 캐시 · **각각의 DB 폴백** | ✅ | `ba20c27` · `c45b260` · `0941c17`. `GeoEquivalenceIT`(캠프 10 × FC 3 **순위 완전 일치**, 거리 오차 2 m) · `GeoFallbackIT`(죽은 Redis, 전제를 첫 어설션으로) · `RedisFcDistancesTest` · `CachingReferenceDataTest` · `GeoIndexLoaderTest` · `GeoMetricsTest` |
| 2a | 레디니스에서 GEO 제외 · 게이지 0/1 · `bypassed` 카운터 | ✅ | `d078f5b`(ADR-016 후속 정정). `GeoFallbackIT.적재가_실패해도_기동하고_게이지가_0_이_된다` |
| 3 | FC 선택 §5.2 1~6단계 · `UNSERVICEABLE` · 순수 함수 | ✅ | `217ec48` · `0a28f8f`(ADR-021 5단계 확정). `FcSelectionTest` 19건 — 세 결손(tier/cold/inventory)이 각각 fallback 사유로, 6개 `UNSERVICEABLE` 사유, `STALE_PLACED` **경계 양쪽 1초** |
| 4 | 애그리거트 둘 · 편입 · 컷오프 스케줄러 · `wave.closed` outbox | ✅ | `217ec48` · `3bcc0ff` · `d5d320c` · `ab3acf3` · `0889303` · `fdd5771`. `WaveTest` 14건 · `WaveStatusTest` 9건(전이표 25조합) · `FulfillmentOrderStatusTest` 7건(전이표 9조합 + 축 규칙) · `FulfillmentPersistenceIT` 11건 · `WaveLifecycleIT` 7건 |
| 4-3 | `plan.completed`·`plan.failed` 계약(소비자 주도)과 웨이브 축 | ✅ | ADR-024(`228e65d`) · `3bcc0ff`. `contracts/events/plan.{completed,failed}.v1.schema.json` + 예시. `WaveLifecycleIT` 3건 |
| 4-1 | `V2__fulfillment_orders.sql` + `wave_orders` 드롭 + 인덱스 EXPLAIN | ✅ | `047b741` · `3b87581`. `docs/benchmarks/phase2-fulfillment-orders-indexes.md`(465만 행, 환경 명시). **부분 인덱스 → 전체 인덱스로 정정**: FK 검사가 부분 인덱스를 쓰지 못해 웨이브 삭제가 7,067 ms(FK 트리거 6,681 ms) → 1.131 ms |
| 4-2 | 정리 배치 30일·90일 (`updated_at`, 종결 상태만, 배치마다 커밋) | ✅ | `72e78a4` · ADR-023(`551cd78`·`5617540`). `FulfillmentRetentionIT` 8건 — 종결만 삭제, 나이는 `updated_at`, FK 순서, 상한 재개, **인덱스를 타는지까지** |
| 5 | 리스너 `order.placed`·`order.cancelled`, 편입 `FOR SHARE`/마감 `FOR UPDATE` | ✅ | `0889303` · `ab3acf3`(ADR-025). `PlanOrderServiceTest` 13건 · `CancelFulfillmentOrderServiceTest` 5건 · `FulfillmentPersistenceIT.편입의_공유_락은_서로_막지_않고_마감의_배타_락은_기다린다` |
| 5-2 | 계획 결과 리스너 둘 + 늦은 `plan.failed` 무시·카운트 | ✅ | `fdd5771`. `RecordPlanResultServiceTest` 6건 · `WaveLifecycleIT`(3전이 + 무시) · `PlanResultListenerTopicsTest`(사유 문자열 고정) |
| 5-1 | order-service: `UNSERVICEABLE`→`FAILED`+사유, `promiseRevised`→약속 갱신 | ✅ | `ab041eb`(#18) · `V3__order_failure_reason.sql`. `ApplyFulfillmentPlanServiceTest` 10건 · `OrderProgressListenerIT` 3건 추가 |
| 6 | 순서 역전 — 별도 마커 없이 `CANCELLED` 행으로 흡수 | ✅ | `0889303` · ADR-022(`988bad1`). `FulfillmentPersistenceIT` 2건 · `FulfillmentOrderTest` · `OrderEventListenerTopicsTest`(`cancelled_before_placed` 문자열 고정) |
| 6-1 | 메트릭 3종 + `event_rejected` 라벨 확장 | ✅ | `ab041eb`(#18 에 스쿼시). `FulfillmentMetricsTest` 4건. `dawnline_wave_orders`·`promise_revised_total`·`fc_fallback_total`, `event_rejected_total{consumer,eventType,reason}` |
| 7 | 테스트 — 이중 마감 · 다음 웨이브 · GEO 폴백 | ✅ | `af08791`(이중 마감 IT 둘) · `PlanOrderServiceTest.마감된_웨이브의_컷오프를_가진_주문은_다음_웨이브로_밀리고_개정된다` · `GeoFallbackIT` |
| 8 | `make demo` | ✅ | `9720520`. `tools/demo/phase2-demo.sh` — DB·브로커 양쪽 확인 |

**2-5 이 요구한 여섯 열** (「마감 대조표에 반드시 열로 들어갈 것」)

| 항목 | 어떻게 증명했나 |
|---|---|
| `fulfillment.planned` 브로커 도착 | `FulfillmentPublishIT.계획된_주문이_브로커에_도착하고_봉투까지_계약을_지킨다`(+ 배차 불가도 같은 토픽). 이 클래스만 릴레이를 켠다 — 처음엔 기반 클래스가 릴레이를 꺼 이 IT 가 조용히 아무것도 안 봤고, 지금은 `전제_릴레이가_돈다` 가 첫 어설션이다 |
| `wave.closed` 브로커 도착 | `WaveLifecycleIT.마감이_주문을_세어_wave_closed_를_브로커로_보낸다` — 봉투 계약 + `payload.waveId` + **키 = `campId`**(§4.5) + `orderCount` 가 마감 시 집계값(3) |
| `plan.completed` 소비 | `WaveLifecycleIT.plan_completed_로_PLANNED_가_된다` — 예시 이벤트를 브로커에 직접 발행, `CLOSED → PLANNED` |
| `plan.failed` 소비 + 재실행 | `WaveLifecycleIT.plan_failed_로_PLAN_FAILED_가_되고_재실행으로_되살아난다` — `CLOSED → PLAN_FAILED → PLANNED` |
| 늦은 `plan.failed` 무시 + 카운트 | `WaveLifecycleIT.계획된_웨이브에_늦게_온_plan_failed_는_무시된다` — 3초 동안 상태가 그대로인 것까지. 사유 문자열은 `PlanResultListenerTopicsTest` 가 고정 |
| 편입 동시성 | `FulfillmentPersistenceIT.편입의_공유_락은_서로_막지_않고_마감의_배타_락은_기다린다` — `FOR SHARE` 둘이 서로 통과하고, `FOR UPDATE` 가 `NOWAIT` 로 막히는 것을 함께 본다 |

| DoD | 상태 | 근거 |
|---|---|---|
| `make demo` — 200건 편입 → 컷오프 → 캠프별 `wave.closed` 1회 | ✅ | 2026-09-05 실행. 주문 200건(편입 199 · 재고결손 1) → 웨이브 29개 마감 → `wave.closed` 29건 **중복 0**, `order_count` 불일치 0. 컷오프는 표가 아니라 `cutoff_at` 을 과거로 밀어 만든다 |
| 이중 마감 없음 테스트 | ✅ | `WaveLifecycleIT` 둘(`af08791`). 세 번째 방어를 일부러 부수면 **fail-open 쪽만 빨개진다** — 실물 락 쪽은 락이 두 번째 인스턴스를 DB 앞에서 돌려보내 통과한다. 그래서 둘 다 둔다 |
| 순서 역전 두 방향 + ADR-022 표 전체 | ✅ | 취소 선착 `FulfillmentPersistenceIT.취소_선착_뒤에_온_order_placed_는_행을_덮지_않는다`, 취소 후착 `취소가_웨이브_소속과_판정_결과를_지우지_않는다` · `WaveLifecycleIT.취소된_주문은_마감_카운트에서_빠진다`. **표의 행이 셋에서 둘로 줄었다** — ADR-025 이후 웨이브 상태별 분기가 사라졌고, 그 사실 자체가 ADR-022 에 정정으로 남아 있다 |
| 24시간 넘은 `order.placed` → `STALE_PLACED` | ⚠️ **단위만** → 7-0 A9 | `FcSelectionTest` 5건(경계 양쪽 1초, 상한이 설정값인 것, FC 선택보다 먼저 판정) · `PlanOrderServiceTest.하루_넘은_컷오프는_STALE_PLACED_다`. 브로커를 지나는 IT 는 없다 — 판정이 순수 함수 안에 있고 시각은 주입된 `Clock` 이라 IT 가 더 볼 것이 없다고 봤다. **DLQ replay 경로가 생기는 Phase 7 에서 다시 본다** — 경로는 Phase 6 묶음 B 에 생겼다(ADR-053, 2026-09-24). 재검토는 예정대로 Phase 7 |
| 정리 배치가 종결 상태만 지운다 | ✅ | `FulfillmentRetentionIT` 8건 |
| `plan.completed`/`plan.failed` 세 전이 + 늦은 실패 무시 | ✅ | `WaveLifecycleIT` 3건 (위 표) |
| **게이트 — §8.3 Bulkhead 판정 기록** | ✅ **Phase 7 유지** | DESIGN §8.3 「Bulkhead 판정 기록」. 원자료 `docs/benchmarks/phase1-orders-k6.md`. 조건(`hikaricp_connections_pending` > 0)은 콜드에서 켜졌으나(191) 원인이 풀 분리로 완화되는 종류가 아니었다 |
| 시드 부족으로 인한 `UNSERVICEABLE` 0건 | ✅ | `ZoneSeedCoverageIT` + `make demo`(이번 실행 200건 중 시드 부족 0). `OUT_OF_STOCK` 은 세지 않는다 — 시드가 §5.2 3단계를 보이려고 **일부러 넣은** 결손이다 |

**Phase 2 마감** (2026-09-05)

이 Phase 가 남긴 것 넷.

1. **측정이 설계 결정을 두 번 뒤집었다.** ADR-022 의 부분 인덱스는 465만 행 EXPLAIN 앞에서
   전체 인덱스로 바뀌었고(FK 검사가 부분 인덱스를 못 쓴다), 편입 락은 `FOR UPDATE` 에서
   `FOR SHARE` 로 바뀌며 `order_count` 증감 로직이 통째로 사라졌다(ADR-025). 둘 다 **일반 규칙
   두 줄**로 §7.1 에 올라갔다 — 이 프로젝트 고유의 교훈이 아니기 때문이다.
2. **"통과했는데 아무것도 증명하지 못하는 테스트" 가 세 번 나왔다.** `GeoFallbackIT` 가 살아
   있는 Redis 를 보고 통과했고, `FulfillmentPublishIT` 는 릴레이가 꺼진 채 돌았다. 세 번째라
   규칙으로 올렸다(CLAUDE.md — 폴백 테스트는 전제를 첫 어설션으로 말한다). 이번 이중 마감
   IT 를 **일부러 부숴 확인한 것**도 같은 습관이다.
3. **복사본을 만들지 않는 쪽을 두 번 골랐다.** §2.2 컷오프 표는 `TierSchedule` 하나로 모으고
   `DeliveryPromise` 가 위임한다(계약 테스트는 회귀 가드로 역할이 바뀌었다). `make demo` 도
   "데모용 짧은 컷오프 표" 대신 `cutoff_at` 을 미는 쪽을 골랐다 — 표가 둘이 되면 갈라진다.
4. **`plan.completed` 는 설계서의 어긋남에서 나왔다.** §4.1 과 §5.2 를 대조하지 않았으면
   "웨이브가 언제 `PLANNED` 인가" 에 답할 이벤트가 없다는 것을 Phase 3 에서야 알았을 것이고,
   4-2 정리 배치는 `PLANNED` 주문 행을 영원히 지우지 못했을 것이다(ADR-024).

---

## Phase 3 — dispatch-service 코어 (룰 엔진 + 기본 최적화)

**작업 순서** (2026-09-05 확정 — 순서 자체가 결정이므로 번호를 그대로 따른다)

`1 → 2 → 2.5 → 3 → 4 → 5a → 5b → 5c → 6 → 7`, 그리고 순서와 무관한 8(릴레이 리더 락).

두 자리가 순서 때문에 존재한다.

- **2.5 가 3 보다 앞**인 이유: 3 의 베이스라인이 "먼저" 인 까닭은 **수치를 남기기 위해서**인데,
  남길 도구가 없으면 3 은 코드만 있고 기록이 없다. 그리고 하네스가 `domain.optimizer` 를 Spring
  없이 그대로 실행한다는 것이 **불변규칙 5 의 존재 이유**이고, 그 사실이 여기서 처음 증명된다.
- **3 이 4 보다 앞**인 이유: §6.9 의 회귀 게이트가 "기본 전략 비용이 베이스라인보다 나쁘면 실패" 다.
  게이트를 켠 뒤에 베이스라인을 만들면 **"그때 무엇과 비교했나" 가 사라진다.**

1. **`domain.optimizer`** (순수 Java, Spring·JPA import 금지 — 불변규칙 5): §6.2 모델
   (`PlanningProblem`·`PlanResult`·`PlanningBudget`), `DistanceProvider`(하버사인), `CostModel`(§6.4),
   `PlanValidator`. 시간·난수는 주입한다(불변규칙 12) — seed 가 같으면 결과가 같아야 한다.

2. **룰 엔진**: `DispatchRule` sealed 계층, §6.3 카탈로그 10종 평가기, `RuleSet` 병합(기본 → 캠프
   오버라이드), `Explanation` 수집.
   이 시점에는 DB 가 없으므로 **룰셋은 테이블이 아니라 픽스처 JSON** 이다. 그 파일을 5a 의
   `R__seed_dispatch` 가 **같이 읽는다** — 두 벌로 두면 갈라지고, 갈라진 날 "테스트는 통과하는데
   운영 룰이 다르다" 가 된다. 드리프트 검사는 `contracts/seed/order-service-geohash5.txt` 와 같은
   방식(양쪽이 각자 검사)으로 둔다.

2.5. **벤치마크 하네스 + 데이터셋 생성기** (`tools/benchmark`, 2026-09-05 완료): seed 고정 생성기로
   `small`(500/5) · `medium`(2,000/20) · `large`(5,000/40) 를 만들고, 전략 실행기와 Markdown 리포트
   출력까지. §6.9 의 지표(총비용·총거리·계획 시간·미배정·지각 stop·차량 사용 대수)를 낸다.
   **Spring 없이 `domain.optimizer` 를 그대로 실행한다** — 못 하면 불변규칙 5 가 깨진 것이다.
   그 사실은 `BenchmarkArchitectureTest` 가 지킨다(Spring·JPA·dispatch 어댑터 의존 금지).

   전략이 아직 없으므로 하네스 안에 **비용 상한 전략 `unassign-all`** 을 둔다 — 아무것도 배정하지
   않고 미배정 페널티만 합산한다. 둘을 한다: 결과를 <em>손으로 계산할 수 있어</em> 하네스 자신을
   검증하고(전략 없이 만든 도구는 스스로 도는지 알 수 없다), §6.9 표에서 **어떤 전략도 넘어서는 안
   되는 상한**이 된다. 이것은 `baseline-nn` 이 아니다 — 베이스라인은 "가장 단순한 <em>진짜</em>
   계획" 이고 이것은 "계획하지 않음" 이라, 서비스가 아니라 도구에 둔다.

3. **`baseline-nn`** + `StopMerger` + 거리 행렬. `DispatchStrategy` 인터페이스·레지스트리.
   여기서 나온 수치가 `docs/benchmarks/phase3-baseline.md` 의 첫 표가 된다.

4. **`sweep-greedy-nn`** (§6.5 1~4단계: `SweepClusterer`·`GreedyAssigner`·`NearestNeighborSequencer`)
   → CI 회귀 게이트를 켠다. **데이터셋은 `medium`** 이다(2026-09-05 결정 — 처음에는 `small` 이라고
   적었다).

   옮긴 근거는 결과가 아니라 메커니즘이다: `small` 은 차량이 5대인데 stop 을 다 실으려면 최소
   4대가 필요해 **"누구를 어느 차에 태울지" 의 자유도가 구조적으로 없다.** 클러스터링은 그
   자유도가 있을 때만 값을 만들고, `medium`(20대)이 그것이 처음 생기는 크기다. `small` 에서
   스윕이 지는 사실은 **지우지 않고** 수치로 남긴다
   (`docs/benchmarks/phase3-baseline.md` §4-5, README 「알려진 레짐」).

   게이트는 `--gate baseline-nn` 이 낸다 — 기준보다 비싼 전략이 있으면 종료 코드 1.

   **게이트 규칙 두 가지** (2026-09-05 확정):
   - **`baseline-nn` 은 게이트가 켜진 순간 동결된다.** 베이스라인이 좋아지면 그때까지의 비교가 전부
     무효가 된다. 바꿔야 하면 `docs/benchmarks/` 에 **재기준(re-baseline) 기록**을 남기고 그때까지의
     수치를 새 기준으로 **다시 낸다.** 동결의 대상은 수치가 아니라 **클래스**이고,
     `BaselineFrozenTest` 가 `BaselineNearestNeighbor.java` 의 SHA-256 을 고정한다. 개선은
     새 전략으로 등록한다(§6.6 레지스트리가 있는 이유다).
   - **게이트는 비용만 본다.** 같은 실행 안에서 두 전략을 돌려 비교하므로 환경에 독립이지만, 계획
     시간은 CI 러너에 따라 흔들린다. 시간은 **기록만 하고 게이트 조건에 넣지 않는다** — 환경 탓으로
     빨개지는 게이트는 결국 꺼진다.

5a. **영속성** (2026-09-05 완료): Flyway V1(§5.3 DDL), 시드 `R__seed_dispatch`(차량 200 ·
   기사 200 · 기본 룰셋 — 2 의 픽스처와 **같은 파일에서 생성**), `fulfillment.planned` 리스너로
   `dispatch_candidates` 적재. 드리프트 검사는 `DispatchSeedCoverageIT` 가 한다.

   **계약에 우선도가 없다** — `fulfillment.planned` 에 `priority` 필드가 없어서 운영에서는 모든
   후보가 0 이고, 따라서 §6.3 의 `PRIORITY_BOOST` 가 한 번도 발화하지 않는다(벤치마크에서는
   생성기가 값을 준다). `serviceTier` 로 유추하면 "DAWN 이 곧 VIP" 라는 정책을 코드가 몰래
   정하는 셈이라 하지 않았다. 우선도의 출처를 정하는 것은 **계약 변경**이다.

5b. **계획 실행** (2026-09-05 완료): `wave.closed` 리스너 → `RunPlan` 유스케이스 → Plan 상태 머신(§5.3,
   `route_plans.wave_id` UNIQUE 로 중복 도착 멱등) → 발행 3종. `PLANNING` 정체 회수 스케줄러
   (10분 경과 → `REQUESTED`).

   - **발행 3종은 같은 outbox 트랜잭션**: `route.assigned`(라우트당) · `order.dispatched`(주문당) ·
     `plan.completed`(웨이브당). 나눠 넣으면 "완료라는데 라우트가 없다" 가 생긴다
     ([ADR-024](adr/ADR-024-plan-completed-event.md)). `plan.failed` 도 같은 계약이 이미 있다.
     두 계약 모두 소비자인 fulfillment 가 Phase 2 에 정의해 두었으므로 여기서는 **만족시키기만** 한다.
     부분 재계획(§6.8)은 `plan.completed` 를 다시 내지 않는다.
   - **발행 직전 재검증**([ADR-026](adr/ADR-026-dispatch-cancellation-window.md) 분기 2, §6.5 6단계):
     후보 상태를 다시 읽어 계획 중에 취소된 주문을 stop 에서 뺀다. 6번이 아니라 여기 있는 이유는
     이것이 취소 처리가 아니라 **발행 경로의 일부**이기 때문이다 — revision 을 쓰지 않고 닫는 자리다.
   - **브로커 도착 IT 3건**: `route.assigned`·`order.dispatched`·`plan.completed` 가 실제 브로커까지
     가서 봉투까지 계약을 지키는지. Phase 1 `OrderPublishIT`·Phase 2 `FulfillmentPublishIT` 와 같은
     형태다 — "outbox 에 들어갔다" 까지만 보면 릴레이와 봉투 조립이 검증되지 않는다.

   **계약 enum 을 하나 넓혔다** — `plan.failed.reason` 에 `NO_CANDIDATES` 를 더했다. 웨이브가
   닫혔는데 계획할 후보가 하나도 없는 경우(전부 취소됐거나 전부 배차 불가로 종결)이고, 스키마가
   "사유가 늘면 같은 major 안에서 enum 을 넓힌다" 고 적어 둔 그대로다(§4.7). 소비자는 이 값을
   문자열로 받아 기록만 하므로 깨지지 않는다.

5c. **REST + 메트릭**: §5.3 표의 6종(plans·routes·reassign·rules·vehicles·drivers). 룰 수정 시
   `rule_version` 증가·이력 보관. `dawnline_plan_duration_seconds`·`plan_cost_krw`·`plan_unassigned`·
   `plan_degraded_total`(§9.1).

6. **`order.cancelled` 소비 — 취소는 최적화 트리거가 아니라 입력 변경이다**
   (2026-09-05 결정, [ADR-026](adr/ADR-026-dispatch-cancellation-window.md), §6.10).
   ADR-017 후속 정정이 **정의한** 경합 창 — 취소는 `PLANNED` 에서 허용되고 `PLANNED` 는 웨이브
   마감 뒤에도 유지되므로 계획 발행과 order-service 의 `order.dispatched` 소비 사이가 그 창이다 —
   을 **닫는 쪽이 여기다.** 5b 뒤에 오는 이유는 취소가 죽일 stop 이 그때 생기기 때문이다.

   dispatch 는 stop 을 죽이고 **이후 stop 의 시간만 재전파**한다. 남은 경로를 다시 풀지 않고
   **순서도 재시퀀싱하지 않는다.** §6.8 재계획 트리거(`delivery.at-risk`, 운영자)는 그대로 둔다 —
   다시 풀 가치가 있는지는 revision 을 받은 tracking 의 ETA 재계산이 정하고, 트리거를 늘리면 같은
   판단을 하는 회로가 둘이 되어 갈라진다. 게다가 취소는 stop 을 빼서 **시간을 벌어 주는** 사건이라
   재계획이 필요한 방향의 반대다.

   분기는 라우트의 출발 여부가 아니라 **stop 의 상태**로 자른다(미출발과 출발 후 미도착은 처리가
   같아 구분이 아무것도 만들지 않는다).

   | 취소 도착 시 상태 | 처리 | 이벤트 |
   |---|---|---|
   | 후보, 계획 전 | `dispatch_candidates.status=CANCELLED` (**삭제 아님** — 설명 가능성) | 없음 |
   | 후보, **계획 진행 중** | 발행 직전 재검증(§6.5 6단계)이 후보 상태를 다시 읽어 stop 에서 뺀다 | 없음 |
   | 발행됨, stop 이 `ARRIVED` **이전** | `route_stops.status=CANCELLED` + 이후 stop 시간 재전파 | `route.assigned` revision+1 |
   | stop 이 `ARRIVED`/`COMPLETED` 이후 | **거부.** 상태 불변 | `dawnline_cancel_too_late_total{camp}` + §9.4 알림 |

   네 번째가 발화하면 order-service 가 `order.dispatched` 를 배송 완료 시점까지 소비하지 못한
   것이므로, 그 카운터는 이상이 아니라 **창의 폭**이고 order-service 의 축 밖 거부 카운터와 한 쌍이다.
   자동 보상은 넣지 않는다 — 물리적으로는 배송됐는데 주문은 `CANCELLED` 인 상태를 ops 가 보게 하는
   것이 이 분기의 역할이다.

   계약: `route.assigned.v1` 의 `revision` 은 **이미 required** 였고(v1 최초 정의부터), `plannedStop` 에
   `status: PLANNED|CANCELLED` 를 **optional·기본 `PLANNED`** 로 추가했다 — v1 을 낸 생산자가 없어
   "부재 = `PLANNED`" 가 실제 사실과 일치하기 때문이다. 취소된 stop 은 페이로드에서 **지우지 않는다**
   (부재는 값이 아니다). 예시 `route.assigned.v1.revised.example.json` 과 계약 테스트 2건이 그것을 고정한다.

   **구현하며 두 가지가 더 나왔다** ([ADR-026 후속 정정 — Phase 3-6](adr/ADR-026-dispatch-cancellation-window.md)).

   - **통합된 stop 의 부분 취소는 stop 의 상태로 말할 수 없다.** `StopMerger` 가 같은 지점·같은
     약속창의 주문을 묶으므로(§6.5 1단계) 세 주문이 실린 stop 에서 하나만 취소되는 일이 일어나고,
     그때 stop 은 여전히 방문하므로 `status` 는 `PLANNED` 다. 그래서 `plannedStop` 에
     **`cancelledOrderIds`**(optional, 기본 `[]`, `orderIds` 의 부분집합)를 더했다 —
     §5.4 의 `shipments` 가 `order_id` PK 라 주문 단위로 알아야 하고, `orderIds` 에서 빼면
     "취소" 와 "다른 라우트로 이동" 을 구별할 수 없다.
   - **네 번째 분기는 Phase 5 까지 발화하지 않는다.** `route_stops.status` 를 `ARRIVED`/`COMPLETED`
     로 옮기는 코드가 없다. 원인은 소비자 목록이었고 **그것은 2026-09-05 에 고쳤다** — dispatch 가
     §4.1 에서 `delivery.status` 의 소비자가 됐다. 미룰 수 없었던 이유는 같은 결손이 §6.8 의
     "미완료 stop 만" 과 §7.2 의 `route:{id}:progress` 도 막고 있었기 때문이다(취소 분기 하나였다면
     미뤄도 됐다). 남은 것은 발행자이고, 그것이 생기는 Phase 5-5 에서 리스너·전이·ADR 을 함께 쓴다.
     그때까지 `dawnline_cancel_too_late_total` 은 구조적으로 0 이고, ADR-026 의 "peak-day 에서 0 이
     아니면" 재검토 조건은 아무것도 검사하지 않는다 — **버그가 아니라 미구현이다.**

7. **마감**: Phase 3 대조표(작업 항목 ↔ 실제 커밋, 빠진 항목은 "미구현" 으로 **표에 남긴다**),
   **5,000건 통합 계획**(DoD, 시간 측정), `docs/benchmarks/phase3-baseline.md` 에
   `baseline-nn` vs `sweep-greedy-nn` 비교표, **냉장 주문이 냉장 차량에만 배정됨을 설명 조회로 확인**(DoD).
   대조표를 목록에 넣는 이유는 Phase 1·2 에서 **매번 무언가를 잡았기** 때문이다 — Phase 1 은 빈 칸
   5개와 레이트 리밋을, Phase 2 는 이중 마감 IT 부재와 고정되지 않은 메트릭 사유 문자열을 잡았다.

8. **릴레이 리더 락 + ADR** (2026-09-05 완료, [ADR-027](adr/ADR-027-outbox-relay-leader-lock.md)):
   서비스당 릴레이 단일 활성을 보장한다(Redis `SET NX` + 갱신을 Lua 한 왕복으로, 배치 전에 매번).
   스케일아웃으로 인스턴스가 2개 이상이 되기 **전에** 들어가야 했다 — 그 전까지는 인스턴스 1개라는
   사실이 §4.4 의 전제를 충족시키고 있을 뿐이었고, `docker compose up --scale` 을 치는 사람은
   그 전제 문장을 읽지 않는다.

   결정에서 두 가지가 이 프로젝트의 다른 락과 갈린다.

   - **판정 불가는 팔로워가 아니다.** `LEADER`/`FOLLOWER`/`UNKNOWN` 셋이고 게이지가 `1/0/-1` 로
     낸다. 발행을 멈추는 결정은 뒤의 둘이 같지만 **봐야 할 곳이 정반대다**(정상 대 Redis 장애).
   - **Redis 장애는 fail-closed** — `RedisWaveLock` 과 반대다. 마감은 멈추면 컷오프 시각을 잃고
     중복 마감은 DB 가 막지만, 여기서 락 없이 진행하면 잃는 것이 **키 단위 순서**이고 DB 에는
     그것을 지킬 수단이 없다(`SKIP LOCKED` 는 중복만 막는다). 멈춰서 잃는 것은 지연뿐이다.
     불변규칙 7 이 요구하는 폴백이 **없는 자리**이고, 그럴 때 답은 없는 폴백을 지어내는 것이
     아니라 멈추는 것이다 — §13 매핑표의 7번 행에 예외로 적었다.

   락을 켠 채로 Redis 가 없으면 **기동에서 실패한다.** 끄려면 `leader.enabled=false` 로 *적는다*
   (ops-api 가 그 값을 쓴다 — 이벤트를 발행하지 않기 때문이고, "인스턴스가 하나다" 와는 다른 이유다).

   **테스트가 바뀐 것을 함께 적는다**: 발행이 브로커까지 가는 것을 보는 IT 셋(`OutboxRelayIT`,
   `OrderPublishIT`, `PlanExecutionIT`)이 이제 Redis 를 띄운다. `OrderPublishIT` 에는 "Redis 는
   있으나 없으나 발행 경로는 같다" 는 주석이 있었고 이 ADR 이 그 문장을 거짓으로 만들었다.
   락을 끄고 통과시키지 않은 이유는 **락을 끄고 통과하는 발행 테스트는 운영 경로를 테스트하지
   않기** 때문이다.

   > **[정정 — Phase 4-0, 2026-09-05]** 위 세 문단 중 *조정자를 Redis 에 둔 부분*은 틀렸다.
   > 릴레이 리더 선출은 서비스 내부 조정이고 그 인스턴스들은 자기 outbox 가 있는 같은 DB 를
   > 공유하므로, `pg_try_advisory_lock` 이면 TTL·갱신·시계가 없고 **DB 가 죽으면 발행할 것도
   > 없으니 fail-closed 딜레마 자체가 사라진다.** §13 에 만든 "명시적 예외" 는 불변규칙 7 의
   > 예외가 아니라 위반이었다. 세 상태 설계와 게이지는 그대로 유지된다. 정정 내용은
   > [ADR-027 후속 정정](adr/ADR-027-outbox-relay-leader-lock.md)에, 작업은 아래 **Phase 4-0**.
   > 마지막 문단도 뒤집힌다 — 발행 IT 셋은 이제 Redis 컨테이너를 **띄우지 않는다.**

**테스트** (각 단계에 붙는다): 룰 평가기 단위(각 룰 위반/통과), 하드 룰 위반 라우트가 최종 산출에
없음(`PlanValidator`), **seed 고정 결정론**(같은 seed → 같은 결과, 불변규칙 12), 5,000 주문 통합
계획(시간 측정), `wave.closed` 중복 도착 멱등.

**DoD**
- `make demo`: 주문 → 웨이브 마감 → 라우트 생성 → `GET /api/v1/plans/{id}`에서 비용·미배정·설명 조회.
- `large` 데이터셋 계획이 완료되고 `docs/benchmarks/phase3-baseline.md`에 baseline-nn vs sweep-greedy-nn 비교표 기록.
- 냉장 주문이 냉장 차량에만 배정됨을 설명(explanation) 조회로 확인.

**마감 대조표** (CLAUDE.md 「작업 방식」 — 기억이 아니라 표로 확인한다)

기준일 2026-09-05, `main` = PR #34 머지 시점. 빠진 항목은 **표에 남긴다**.

| # | 작업 | 상태 | 커밋 / 근거 |
|---|---|---|---|
| 1 | `domain.optimizer` 모델·거리·비용·검증 (순수 Java) | ✅ | #21 `7d5076f`. `BenchmarkArchitectureTest` 가 Spring·JPA 의존 0을 지킨다 |
| 2 | 룰 엔진 — sealed 계층, 카탈로그 10종, `RuleSet` 병합, `Explanation` | ✅ | #22 `eda1e8e`. 픽스처 JSON 을 5a 의 `R__seed_dispatch` 가 같이 읽고, 드리프트는 `DispatchSeedCoverageIT` 가 본다 |
| 2.5 | 벤치마크 하네스 + 데이터셋 생성기 + `unassign-all` 상한 | ✅ | #23 `642a37d` |
| 3 | `baseline-nn` + `StopMerger` + 거리 행렬 | ✅ | #24 `52d6bb9`. 첫 표가 `phase3-baseline.md` §1 |
| 4 | `sweep-greedy-nn` (§6.5 1~4단계) | ✅ | #25 `d7633aa`, 자르는 규칙 수정 #28 `38fb956` |
| 4 | CI 회귀 게이트를 **켠다** | ✅ | #30 `2960d9b`. **#25 에서는 켜지지 않았다** — job 이 `if: false` 였고 인자의 `sweep-greedy-nn+ls` 는 없는 전략이었다. 아래 「대조표가 잡은 것」 |
| 5a | Flyway V1 · 시드 · `fulfillment.planned` 후보 적재 | ✅ | #26 `6f313d3` |
| 5b | `wave.closed` → `RunPlan` → Plan 상태 머신 → 발행 3종 | ✅ | #27 `a007b96`. 브로커 도착 IT 3건은 `PlanExecutionIT` |
| 5c | 운영자 REST 6종 · 룰 버전·이력 · 계획 메트릭 4종 | ✅ | #29 `f9f2fe3` |
| 6 | `order.cancelled` 소비 — 네 분기, 시간 재전파, 개정 발행 | ✅ | #31 `79cd5da`. 순번 UNIQUE 지연 제약(V3)은 #30 |
| 7 | 마감 — 대조표 · 5,000건 통합 계획 · 냉장 DoD · `make demo` | ✅ | #34 (이 커밋) |
| 8 | 릴레이 리더 락 + ADR-027 | ✅ | #33 `6eadc2b`. **조정자는 Phase 4-0 에서 Redis → PostgreSQL advisory lock 으로 정정됐다**(같은 날 리뷰). 세 상태·게이지·「배치 전 매번 확인」은 그대로다 |
| — | **`rules:camp:{id}:v{n}` 룰셋 캐시 (§7.2)** | ⬜ **미구현** | 넣지 않았다. 룰은 캠프당 10행 남짓이고 계획 한 번에 한 번 읽는다 — 데모 29계획에서 룰 조회가 29회다. 이 규모에서는 DB 조회가 맞다. 재검토 지점: **Phase 7 로 못박았다**(2026-09-05) — 측정 전에는 필요 없다. 부분 재계획(§6.8)이 라우트마다 룰을 다시 읽게 되는 Phase 5 이후, 룰 조회가 실제로 보이는지 재 보고 정한다 |
| — | **`lock:plan:{waveId}` 이중 안전장치 (§5.3, §7.2)** | ❌ **설계에서 제거** (2026-09-05) | 미구현으로 적어 둔 판단이 그대로 결정이 됐다. 중복 계획을 막는 것은 `route_plans.wave_id UNIQUE` 이고(5b) 그것은 DB 제약이라 인스턴스 수와 무관하다. 두 번째 장치는 없는 문제를 막으면서 **폴백을 정해야 하는 Redis 키를 하나 늘린다** — 그 비용이 이득보다 크다. §5.3·§7.2 에서 지웠다 |
| — | **`PRIORITY_BOOST` 가 운영 경로에서 발화하지 않는다 (§6.3)** | ✅ **닫힘 — Phase 4-11** (이 표를 쓴 시점에는 ⚠️ 계약 결손) | `fulfillment.planned` 에 `priority` 가 없어 후보가 전부 0 이다(5a 에 적어 둔 그대로). 벤치마크에서는 생성기가 값을 준다. `serviceTier` 로 유추하면 "DAWN 이 곧 VIP" 를 코드가 몰래 정하는 것이라 하지 않았다. 우선도의 출처를 정하는 것은 **계약 변경**이고, Phase 4-10(미배정 선택 규칙)이 이 값을 필요로 한다 **(2026-09-25, Phase 7-0 대조표가 잡았다)** 계약을 바꾸지 않고 닫혔다 — 우선도는 이미 받은 사실(`promiseRevised` · `requiresCold`)에서 적재 시점에 파생한다([ADR-028](adr/ADR-028-unassigned-policy.md), `LoadCandidateService`). 이 행만 갱신되지 않은 채 남아 있었다 |
| — | **§6.10 넷째 분기가 발화하지 않는다** | ✅ **닫힘 — Phase 5-5**(이 표를 쓴 시점에는 ⚠️ Phase 5) — `17e31db`(#41), [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md). **(2026-09-25, 7-0 대조 검사가 잡았다)** | `route_stops.status` 를 옮기는 코드가 없다. §4.1 소비자 목록은 #32 에서 고쳤고(dispatch 가 `delivery.status` 소비자), 구현은 발행자가 생기는 Phase 5-5 |

**테스트 항목** (Phase 3 「테스트」 목록)

| 항목 | 상태 | 근거 |
|---|---|---|
| 룰 평가기 단위 (각 룰 위반/통과) | ✅ | `rule/` 패키지 단위 테스트, 카탈로그 10종 |
| 하드 룰 위반 라우트가 최종 산출에 없음 | ✅ | `PlanValidatorTest` + §6.5 6단계 재검증 |
| seed 고정 결정론 (불변규칙 12) | ✅ | 전략 단위 테스트 + 벤치마크가 같은 seed 로 같은 수를 낸다(`phase3-baseline.md` 재현 확인) |
| **5,000 주문 통합 계획 (시간 측정)** | ✅ | `PhaseThreeDoDIT.오천건_계획이_예산_안에_끝난다` — 아래 수치 |
| `wave.closed` 중복 도착 멱등 | ✅ | `route_plans.wave_id` UNIQUE + 리스너 IT |

**DoD**

| DoD | 상태 | 근거 |
|---|---|---|
| `make demo`: 주문 → 웨이브 마감 → 라우트 생성 → `GET /plans/{id}` 에서 비용·미배정·설명 | ✅ | 2026-09-05 실행. 주문 200 → 웨이브 29 마감 → 계획 29 → 라우트 63 → `route.assigned` 63건(고유, 키=routeId 확인) → 계획 조회에 비용 410,522원·배정 14·미배정 1(`shift-window`)·설명 15줄. `tools/demo/phase3-demo.sh` |
| `large` 계획 완료 + `phase3-baseline.md` 에 비교표 | ✅ | §1·§4-4·§4-5. `large` 5,000/40 에서 `baseline-nn` 14,005,048 대 `sweep-greedy-nn` 13,610,957 (−2.8%) |
| 냉장 주문이 냉장 차량에만 배정됨을 **설명 조회로** 확인 | ✅ | 두 곳에서. `PhaseThreeDoDIT.냉장_주문은_냉장_차량에만_배정된다_설명_조회로_확인`(400건, 위반 0, 비냉장 차량도 쓰임) + `make demo` 3단계(냉장 후보 55 · 배정 48 · **위반 0** · 쓰인 비냉장 차량 17) |

**5,000건 통합 계획 — 측정** (2026-09-05, `PhaseThreeDoDIT`)

| 항목 | 값 |
|---|---|
| 환경 | Mac OS X 27.0 (aarch64) · OpenJDK 25.0.4.1 · 14 코어 · 힙 16,384 MB · G1 · PostgreSQL 18.2 (Testcontainers) |
| 입력 | 캠프 1개 · 후보 5,000 · 차량 20대(시드) · 근무창 06:00–22:00 KST · 계획 시각 09:00 KST 고정 |
| **계획 시간** | **4,916 ms** (§6.7 목표 p95 ≤ 30초 — 6분의 1) |
| 왕복(유스케이스 전체) | 25,311 ms |
| 결과 | 라우트 20 · 배정 3,810 · 미배정 1,190 · 비용 39,102,032원 |

세 가지를 함께 읽어야 한다.

1. **계획 시간(4.9초)과 왕복(25.3초)은 다른 것을 잰다.** 서비스가 재는 `planDurationMs` 는
   후보 적재 조회 + 룰 조회 + 최적화까지이고, 나머지 20초는 라우트 20개·stop 3,810개·설명
   5,000행의 **영속화**다. §6.7 의 목표는 앞의 값이다.
2. **벤치마크의 `large`(674 ms)와 비교하지 않는다.** 저쪽은 5,000/40 이고 이쪽은 5,000/20 이라
   용량이 절반이다 — 그리디가 실행 가능 차량을 못 찾아 클러스터를 쪼개고 다시 시도하는 경로를
   훨씬 많이 지난다. 그리고 저쪽에는 DB 가 없다.
3. **미배정 1,190건은 알고리즘이 아니라 용량이다.** 한 캠프의 20대가 근무창 안에 넣을 수 있는
   양이 그만큼이다. `phase3-baseline.md` §2-1 이 데이터셋을 고칠 때 쓴 것과 같은 판정이다.

**측정이 재현되게 만드느라 두 번 고쳤다.** 처음에는 같은 입력에서 배정 수가 906·898·922 로
흔들렸다.

- **주문 id 가 매번 달랐다.** `Ids.newId()` 는 UUIDv7 이라 시각에서 나오고, stop 통합과 동률
  처리의 순서가 그 id 를 따라 바뀐다. 불변규칙 12 의 "seed 가 같으면 결과가 같다" 는 *입력이
  같을 때*의 이야기이고, 벤치마크 하네스는 생성기가 id 까지 만들어 그 조건을 만족시킨다 —
  픽스처가 그 일을 해야 했다.
- **실행 시각이 근무창을 잘랐다.** 차량 근무창은 벽시계 `TIME` 이고 어댑터가 계획 날짜에 붙인다.
  20시에 돌리면 남은 창이 두 시간이라 오전과 배정 수가 완전히 달라진다. 시계를 09:00 KST 로
  **옮기되 멈추지는 않았다** — `Clock.fixed` 로 세우면 서비스가 재는 `planDurationMs` 가 0 이
  되어 "30초 이하" 어설션이 언제나 참이 된다. 공허한 테스트가 하나 더 늘 뻔했다.

**대조표가 잡은 것 넷**

| 잡은 것 | 왜 표 없이는 안 보였나 | 처리 |
|---|---|---|
| **회귀 게이트가 꺼져 있었다** | 4번 항목은 "게이트를 켠다" 였고 코드는 있었다. job 이 `if: false` 이고 인자가 없는 전략 이름(`sweep-greedy-nn+ls`)이라는 것은 **CI 가 초록이었기 때문에** 보이지 않았다 — 없는 job 은 실패하지 않는다 | #30 에서 켰다. 5c 리뷰의 "켜져 있다고 믿는 게이트는 없는 게이트보다 나쁘다" |
| **`make demo` 가 dispatch 가 없다는 전제에 기대고 있었다** | Phase 2 데모는 "CLOSED 웨이브 29개" 를 기다렸다. Phase 3 에서 dispatch 가 계획을 돌리면 `plan.completed` 가 웨이브를 `PLANNED` 로 옮기므로(ADR-024) 그 문장은 **거짓이 된다**. 데모를 실제로 돌리기 전까지 아무도 몰랐다 | 판정을 "OPEN·CLOSING 을 벗어났다" 로 바꿨다. 그 뒤로 얼마나 갔는지는 phase3-demo.sh 가 본다 |
| **Compose 스모크 job 이 켜져도 실패했을 것이다** | `needs: [build, image]` 인데 `image` 는 **다른 러너**에서 빌드한다. 이미지가 로컬 데몬에 없으니 `make up` 의 `check-images` 에서 죽는다. 꺼 둔 job 의 결함은 켜기 전까지 보이지 않는다 | `needs: build` + `make images` 단계. 2026-09-05 활성화 |
| **§3.2 의 dispatch 구독 목록에 `order.cancelled` 가 없었다** | §4.1 토픽 표에는 있었다. 두 표가 어긋난 채로 6번이 구현됐다 | #32 에서 채웠다 |

**Phase 3 마감** (2026-09-05)

세 가지가 이 Phase 의 값이다.

1. **측정이 설계를 세 번 고쳤다.** 데이터셋이 물리적으로 불가능했고(수요/용량 126%), 자전거가
   이 규모에 들어갈 수 없었고, 4단계의 대기시간 항이 일어나지 않는 일에 돈을 물리고 있었다.
   셋 다 알고리즘을 의심하기 전에 **용량을 재서** 나왔다(`phase3-baseline.md` §2).
2. **지는 표를 남겼다.** `small` 에서 기본 전략이 베이스라인보다 8.8% 비싸다. 게이트는 자유도가
   생기는 `medium` 으로 옮기되 그 사실은 수치·분해·메커니즘과 함께 세 곳에 남겼다 — 이기는
   항(지각 stop 14 → 5)까지 같이.
3. **전제를 코드가 강제하게 만들었다.** §4.4 의 "단일 활성 인스턴스" 는 지금까지 인스턴스가
   하나라는 사실이 지키고 있었다. ADR-027 이 그것을 락으로 옮겼고, 그 과정에서 "Redis 장애에
   fail-closed" 라는 이 저장소의 첫 예외가 §13 매핑표에 기록됐다.

---

## Phase 4 — 최적화 고도화·열화 모드·벤치마크 리포트 (Staff 차별화)

**순서** (2026-09-05 결정): **0번 → 1번 → 8·10번 → 나머지.** 0번이 앞인 이유는 작고 독립적이며
*가용성 회귀를 없애는* 일이기 때문이다 — 알고리즘 작업 뒤로 미루면 그동안 Redis 한 대가 다섯
서비스의 발행 가용성 상한으로 남는다. 11번(ADR-028)은 10번 착수 전에 확정한다.

**작업**
0. **릴레이 리더 락을 PostgreSQL advisory lock 으로** — [ADR-027 후속 정정](adr/ADR-027-outbox-relay-leader-lock.md). ✅ 완료.
   조정자를 서비스 밖(Redis)에서 *outbox 가 들어 있는 그 DB* 로 옮긴다. TTL·갱신·시계가 사라지고,
   DB 가 죽으면 발행할 행도 읽지 못하므로 fail-open/fail-closed 딜레마 자체가 없어진다.
   구현 조건 넷은 ADR 에 있다 — 전용 장수 세션, 매 사이클 `pg_locks` 에서 도출(획득을 먼저
   시도하면 참조 수가 쌓인다), 세션 끊김 시 재연결, `SKIP LOCKED` 는 인수인계 안전망으로 유지.
   같은 작업에서 §5.3 의 `lock:plan` 을 설계에서 빼고, §13 에 「꺼 둔 검증은 실패하지 않는다」를
   두 사례와 함께 남긴다.
1. **`LocalSearchImprover` — ✅ 완료** (2026-09-09, [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md) · [측정](benchmarks/phase4-local-search.md)).
   2-opt · Or-opt · inter-route relocate(1~3개 묶음, 양 방향) · swap. 종료 조건 셋 —
   국소 최적 · 개선 폭 < 0.1% · 예산 소진.

   | 데이터셋 | `baseline-nn` | `sweep-greedy-nn` | `sweep-greedy-nn+ls` | 계획 p95 |
   |---|---:|---:|---:|---:|
   | small | 1,510,366 | 1,642,762 | **1,451,439 (−3.90%)** | 251 ms |
   | medium | 5,032,092 | 4,581,241 | **4,185,463 (−16.82%)** | 1,404 ms |
   | large | 14,026,147 | 13,610,957 | **12,964,445 (−7.57%)** | 3,247 ms |

   **`large` 의 15% 는 이 작업으로 닿지 않는다.** 총비용의 **36.8% 가 미배정 페널티**이고 개선
   단계는 그 항을 보지 못한다 — 거리비를 0 으로 만들어도 목표에 22만원이 모자란다. 값을 만들 이동은
   「미배정 stop 을 실행 가능한 라우트에 끼워 넣기」이고 §6.5 5단계의 네 이동 목록 밖이다.
   미배정 99건(전부 위험물)의 절반만 실어도 `large` 는 **−24%** 다. **10·11번과 한 결정으로**
   다뤄야 한다 — "누구를 남기는가" 와 "남긴 것을 다시 싣는가" 는 같은 규칙의 앞뒤다.

   > **재기준 주의**: 위 표는 14번 이전의 수치다. 현재 값은 [`phase4-constraint-classes.md`](benchmarks/phase4-constraint-classes.md) §4.

   **곁가지로 드러난 것**: `RouteState.append` 가 O(n) 복사 두 번 + `zones()` 가 호출마다 전체
   훑기라 **라우트 재구성 하나가 O(n²)** 였다. 사슬 + 권역 누적으로 바꾸자 `large` 의 탐욕 단계가
   **18.4초 → 1.2초(15.5배)** 이고 **여섯 칸의 비용은 한 자리도 바뀌지 않았다.** 개선 단계가 없었으면
   보이지 않았을 것이다 — 탐욕은 예산의 61%를 쓰고도 "예산 안" 이었다.
2. **`savings-cw+ls` 전략 — ✅ 완료** (2026-09-17,
   [ADR-042](adr/ADR-042-savings-merges-are-class-aware.md) ·
   [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) ·
   [측정](benchmarks/phase4-savings-cw.md)).

   §6.6 표의 한 줄이 말하지 않은 것이 셋이었다 — 완전한 savings 목록은 `peak` 에서 3,500만 쌍이고
   (쌍은 **개선 단계와 같은 K-최근접 표**에서만 만든다), CW 는 차량 없이 라우트를 만들며
   (실행 가능성은 **합집합 조합을 덮는 차량 중 가장 큰 것**으로 잰다), **stop 을 이어 붙이며 제약
   조합을 키운다**. 셋째가 핵심이다: 붙이는 시점의 좌석 예약([ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md))은
   이미 만들어진 라우트를 고칠 수 없으므로, **집계 좌석 불변식을 구성 단계로 옮겼다.**

   | 데이터셋 | `baseline-nn` | `sweep-greedy-nn+ls` (기본) | **`savings-cw+ls`** | 종료 |
   |---|---:|---:|---:|---|
   | small | 1,517,523 | 1,113,911 (−26.60%) | **1,073,363 (−29.27%)** | 수렴 |
   | medium | 4,588,065 | 3,791,148 (−17.37%) | **3,705,570 (−19.24%)** | 수렴 |
   | large | 9,577,578 | 8,147,294 (−14.93%) | **7,729,298 (−19.30%)** | 수렴 |
   | peak | 28,369,930 | **21,509,847 (−24.18%)** | 23,277,318 | ⚠️ savings **잘림** |
   | overload | 200,727,447 | 153,199,207 | **111,436,423** | 수렴 |

   > **이 표는 4-19 가 다시 냈다** (2026-09-17, [ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) ·
   > [측정](benchmarks/phase4-endpoint-merges.md)). `peak` 이 수렴하고(20,737,822) `small` 이
   > 나빠졌다(1,262,833). 위 수치는 **4-2 시점의 기록**으로 남긴다.

   **Phase 4 DoD 「베이스라인 대비 ≥ 15%」가 `large` 에서 −19.30% 로 넘었다** — 6,353원을 쫓지
   않기로 하고 다음 항목으로 간 결과이고, 넘긴 것은 자도 축도 아니라 **구성 방식**이다.

   **그런데 기본 전략은 바꾸지 않았다.** `peak` 에서 30초 예산에 잘리고 **잘린 값이 실행마다
   다르다**(20,940,782 ~ **23,277,318** — 넷째 실행은 마감이 개선이 아니라 **배정**을 끊어
   96 stop 이 `plan-deadline` 로 남았고 기본보다 +1,767,471원). 바꾸는 조건은 미리 적었고
   **벽시계가 아니라 구조다** — 「`peak` 구성 라우트 수 ≤ 차량 수 × 1.2(= 106)」, 지금은 **216**.
   부족분 3초의 **97%가 재삽입**이고(구성 241 ms · 부착 170 ms), 그 부하를 정하는 것이 라우트
   수다 — 216개 중 128개가 밀려 3,880 stop 이 내려간다. 조건을 만드는 항목은 **4-19** 다.

   재 보고 넣지 않은 것: **savings 목록의 다중 패스** — `large`·`peak` 모두 두 번째 패스가 한
   건도 더 잇지 못한다(단일 패스가 이미 고정점).
3. **병렬화 — ⏸ 게이트 뒤로 미뤘다** (2026-09-11, [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) · [측정](benchmarks/phase4-where-the-time-is.md)).

   원래 문장은 「클러스터 병렬 처리(ForkJoin), Kafka/I/O는 가상 스레드
   (`spring.threads.virtual.enabled=true`) — 성능 전후 측정」이었다. 착수 전에 `large` 계획 하나를
   단계별로 재 봤더니 **§6.7 의 전제가 두 군데에서 거짓**이었다.

   | `large` 5,773 ms | 시간 | 비중 | 실제 병렬 단위 |
   |---|---:|---:|---|
   | 1·2 통합·클러스터링 | 19 ms | 0.3% | — |
   | 3 탐욕 배정 | 1,548 ms | 26.8% | 클러스터가 아니라 **한 클러스터 안의 차량 40대** |
   | 5 개선 — 라우트 안 | 444 ms | 7.7% | **라우트** |
   | 5 개선 — 라우트 사이 | 3,680 ms | **63.7%** | 라우트 **쌍**(클러스터로 자르면 사라진다) |

   - **클러스터는 독립이 아니다** — 배정이 하나씩 붙이며 차량 상태를 바꾼다.
   - **개선은 클러스터 단위가 아니다** — 시간의 88%가 라우트 *쌍*을 본다.
   - 그리고 **시간이 어디 있는지는 시간이 문제인지를 말하지 않는다.** `large` 는 30초 예산의
     19%만 쓰고 수렴한다 — 코어를 더 줘도 *같은 답을 더 빨리* 낼 뿐이다.

   **게이트를 돌렸다 — 결과는 이월** (2026-09-11, [측정](benchmarks/phase4-peak-gate.md)).
   기준은 먼저 적었다: `peak`(15,000/60)에서 30초 예산이 실제로 잘라 내는지, 잘림의 대가가 120초
   실행 대비 1% 미만이면 이월.

   | 예산 | 개선 패스 | 개선 종료 | 총비용 | 계획 시간 |
   |---|---:|---|---:|---:|
   | 30초 | 8 | **예산 소진** | 147,956,375 | 42.5 ~ 43.2초 |
   | 120초 | 9 | **수렴** | 146,736,007 | 44.9초 |

   **잘림의 대가 = +0.83%** → 기준대로 **이월**한다. 그리고 게이트는 병렬화가 애초에 표적을
   못 맞춘다는 것도 보여 줬다 — `peak` 의 계획 시간 61%는 **재삽입**이고 개선은 29.7%다.
   개선을 코어 14개로 0초까지 줄여도 43.2 → 30.4초다(16번).

   가상 스레드(`spring.threads.virtual.enabled=true`)는 이 게이트와 무관한 I/O 축이라 7번
   (ADR-008 확정)과 함께 간다 — ADR-008 이 이 이월을 수치와 함께 기록한다.
4. **FAST 모드 — ✅ 완료** (2026-09-10, [ADR-034](adr/ADR-034-degrade-mode.md) · [측정](benchmarks/phase4-fast-mode.md)).

   **뼈대는 이미 있었고 비어 있던 것은 가운데 한 칸이었다.** `PlanMode` enum · `RunPlanCommand.mode` ·
   `PlanController` 의 `mode` 파라미터 · `dawnline_plan_degraded_total` 이 전부 있었는데 **그 `mode` 가
   전략에 닿지 않았다** — `mode=FAST` 로 돌려도 개선 단계가 그대로 돌았다. 열화는 기록되기만 하고
   아무것도 생략하지 않는 이름이었고, 그 상태의 카운터는 "성수기를 이렇게 넘겼다" 가 아니라 아무 일도
   하지 않은 라벨의 개수를 세고 있었다.

   | `sweep-greedy-nn+ls` | 계획 p95 FULL → FAST | 총비용 FULL → FAST | 열화의 대가 |
   |---|---:|---:|---:|
   | small | 270 → **63 ms** | 1,490,513 → 1,624,788 | +9.01% |
   | medium | 1,283 → **542 ms** | 3,919,106 → 4,290,728 | +9.48% |
   | large | 5,872 → **1,569 ms** (3.7배) | 8,276,130 → 9,053,096 | **+9.39%** |

   §6.7 의 「같은 조건 fast mode ≤ 5초」를 `large` **1,569 ms** 로 통과한다. 그리고 이 표가 FAST 의
   존재 이유다 — FULL 의 5,872 ms 는 30초 예산 안이지만 **5초 목표는 넘는다.**

   - **FAST 가 생략하는 것은 §6.5 5단계 하나다** (전제는 [ADR-028](adr/ADR-028-unassigned-policy.md) 이
     이미 정했다). `SweepGreedyNearestNeighborTest.FAST_는_개선_단계만_생략한다` 가 **FAST 결과 ==
     개선 단계 없는 전략 결과**를 한 자리까지 확인하고, 「개선 단계가 이 문제에서 실제로 값을 만든다」를
     첫 어설션으로 말한다. **전략 이름은 바뀌지 않는다** — 이름은 「무엇을 쓰려 했는가」, 모드는
     「무엇을 포기했는가」다.
   - **열화는 사다리다** (같은 날 후속 정정 — **아래 표가 이 결정을 고쳤다**). 처음에는 두 조건이
     같은 처방(FAST)을 냈는데, 예산 5초 실험이 두 수를 나란히 놓았다: 패스 단위 예산이 개선을
     끊은 대가 **+0.21%** 대 FAST 의 대가 **+9.4%**. **45배 비싼 처방이었다.** §6.7 의 예산 조건은
     [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md) 의 패스 단위 예산이 생기기
     *전에* 쓰인 문장이고, 예산이 이미 계획 시간을 상한으로 묶는 지금 「예산에 가까웠다」는
     과부하가 아니라 「개선이 예산을 다 썼다」일 뿐이다. **처리량 부족을 말하는 신호는 랙 하나다.**

     | 단 | 조건 | 처방 | `large` p95 | 대가 | 아낀 시간당 |
     |---|---|---|---:|---:|---:|
     | 아래 | 직전 > 예산×0.8 | 개선 예산 × 0.5 (FULL) | 4,898 → **3,208 ms** | **+1.35%** | **56원/ms** |
     | 위 | 랙 > 3 | 개선을 끈다 (FAST) | → **1,542 ms** | **+9.39%** | 232원/ms |

     **기본 30초 예산에서는 계수가 아무것도 하지 않는다** — 계수 1.0 과 0.5 의 결과가 한 자리도
     다르지 않다(둘 다 8,276,130). 개선 단계가 「개선 폭 < 0.1%」로 먼저 멈추기 때문이고, 그래서
     아랫단은 예산이 *실제로* 조일 때만 작동한다. 진동은 아랫단에만 있고 진폭이 +1.35% 라
     평균 +0.78% 이며, 그 진동이 평균 계획 시간을 4,898 → 4,053 ms 로 사 온다.
   - **랙은 선행 지표라** §8.2 의 버스트를 그 순간에 본다. 직전 계획 시간은 후행이다.
   - **랙은 `Consumer#currentLag`(KIP-695).** Micrometer 게이지를 읽지 않는다 — 제어 입력을 관측
     지표에서 읽으면 지표 이름이 바뀔 때 `NaN` 이 「랙 없음」으로 읽혀 판단이 조용히 멈춘다.
     값은 **파티션** 단위라 「이 캠프의 랙」이 아니라 「이 캠프가 실린 소비 흐름의 랙」이다.
   - **모름은 0이 아니다.** 사유 다섯(`REQUESTED`·`LAG`·`BUDGET`·`LAG_UNKNOWN`·`NONE`) 중
     `LAG_UNKNOWN` 이 `NONE` 과 따로 있는 이유이고, 그 수는 새 카운터
     `dawnline_plan_backlog_unknown_total` 이 센다. **사람이 지정한 FAST 는 열화로 세지 않는다.**
   - **사유는 계획 행에 남는다**(`route_plans.mode_reason`, V5) — 카운터는 추세, 행은 개별 답.
   - **인덱스는 넣지 않았다** (불변규칙 11). 기준을 먼저 쓰고(계획 시간의 1% = 58 ms) 쟀다:
     10만 행에서 순차 스캔 **5.441 ms**(0.09%) 대 인덱스 0.025 ms. 218배지만 절대값이 기준의 1/10
     아래다. **재검토 조건은 「캠프 100개」 또는 「`route_plans` 100만 행」.**
   - `small` 에서 FAST 는 베이스라인보다 **+6.09%** 비싸다. 숨기지 않는다 — 다만 `small` 은 FULL 로도
     p95 가 270 ms 라 애초에 열화 조건에 닿지 않는다.
5. **벤치마크 마감 리포트 — ✅ 완료** (2026-09-18, [측정](benchmarks/phase4-strategies.md)).

   계획 문장은 「4개 데이터셋 × 3~4 전략 × 5회」였고, **다섯 데이터셋 × 네 전략**으로 냈다
   (`overload` 는 **16-a** 가 갈라낸 과부하 절이라 같은 표에
   놓지 않는다). 파일 이름은 계획의 `<date>-strategies.md` 가 아니라 **`phase4-strategies.md`** 다 —
   저장소의 `docs/benchmarks/` 가 전부 `phase<N>-<주제>.md` 이고, 같은 주제를 다시 잰 문서를
   나란히 놓으려면 날짜보다 주제가 낫다. §6.9 의 문장도 그 규약으로 고쳤다.

   | 절 | 내용 |
   |---|---|
   | §1 | 실현 가능한 네 데이터셋 — 총비용·미배정·차량·거리·p50/p95·지각 + **비용 분해** |
   | §2 | 과부하 `overload` 별도 절 (비용의 85~93%가 미배정 페널티다) |
   | §3 | **고정비 하한** — 불가능의 경계, 전략별 두 열 |
   | §4 | **사다리 두 단** — 윗단 FAST 의 대가가 **전략마다 3배 다르다**, 아랫단은 [ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md) 뒤 첫 재측정 |
   | §5 | **재기준 이력 여섯 줄** + 동결 계약이 지키는 것과 지키지 않는 것 |
   | §6 | **알려진 레짐 둘** — 첫째(sweep `small`)는 닫혔고 둘째(savings `small`)는 열려 있다 |
   | §7 | **§6.9 방법론** — 그림자 계측 원장 여덟 줄 · 짝 문장 셋 · 마지막 계측(클러스터 여유, 닫음) |
   | §8 | Phase 4 DoD 대조 |

   **다섯 데이터셋 · 네 전략이 전부 「수렴 종료」다** — 어느 회차도 계획 마감에 잘리지 않았고,
   그래서 이 표의 비용은 재현 가능하다(§6.9 재현 조건).
6. (선택) `timefold` 전략 실험 → ADR-004 결론에 수치 반영. 기본 경로에 포함하지 않는다.
   — **⛔ 범위에서 뺀다** (2026-09-18, [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)). 비교 대상을 외부 솔버에서 **완화 하한**으로
   옮겼다. Phase 7-6 에 여유가 있으면 `medium` 한 개만, 공정성 셋(같은 목적함수·하드 룰·예산·seed)을
   맞춘 채로.
7. ADR-004, 008 확정. — **004 ✅ 확정**(2026-09-18), **008 ⏸ Phase 7-6**(3번 이월로 근거가 바뀌었다).
8. **`small` 레짐 격차 — ✅ 닫혔다** (2026-09-09, [측정](benchmarks/phase4-local-search.md) §4).
   `sweep-greedy-nn+ls` 가 `small` 에서 `baseline-nn` 보다 **−3.90%** 다(스윕 단독은 +8.8% 였다).
   **14번의 재기준 뒤에는 −2.68% 다** — 통합 키에 제약이 들어가면서 여유가 얇아졌다.
   격차는 여전히 닫혀 있지만 그 사실을 README 의 레짐 문단이 함께 적는다.
   분기 조건대로 **항목 종료** — 클러스터 수를 비용 결정으로 만드는 일은 하지 않는다.

   **진단은 맞았고 함의만 틀렸다.** "다섯째 차의 고정비 45,000원은 국소 탐색이 없애지 못한다" 는
   정확했다 — 다섯째 차는 그대로 있다. 넘긴 것은 나머지 항이다: 거리 −47,101 · 시간 −41,257 ·
   소프트 −35,569 대 고정비 +45,000 · 미배정 +20,000, 합 **−58,927**.

   **2026-09-12 — 다시 열었고, 만들었고, 넣지 않았다**
   ([ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) ·
   [측정](benchmarks/phase4-cluster-axis.md)). §6.9 규칙대로 구현 전에 상한을 쟀더니 **진단이
   두 칸 위**를 가리켰다 — `targetClusters` 가 「차 한 대 몫」을 중량·부피로만 재는데
   **클러스터 64개가 전부 stop 축에 묶이고**(`bindCap = 0`), 그 「차 한 대」는 **가장 큰 차**라
   함대의 절반인 밴에는 애초에 안 들어간다(`large` 899 stop · `peak` 1,966 stop).

   | 조합 | `small` | `medium` | `large` |
   |---|---|---|---|
   | **A** 현재 | 1,490,513 (−2.67%) | 3,919,106 (−15.47%) | **8,276,130 (−14.52%)** |
   | **B** 설계대로 배정만 | 1,458,623 (−4.76%) | **3,780,183 (−18.47%)** | 9,311,402 (−3.83%) |
   | **C** 축 정정만 | **1,432,560 (−6.46%)** | 3,934,743 (−15.14%) | 8,428,761 (−12.95%) |
   | **D** 둘 다 | 1,452,102 (−5.18%) | 3,896,595 (−15.96%) | 8,435,944 (−12.87%) |

   축 정정은 `small` 격차를 **두 배 넘게** 벌어 주는데 DoD 가 걸린 `large` 가 1.65%p 나빠진다.
   손해는 거리가 아니라(오히려 76 km 줄었다) **소프트 룰 125,523원**이고, 그중 83,923원이
   `priority-boost` 의 `÷ position` — 라우트가 길어지면 우선 고객 보너스가 준다.

   **그 뒤에 더 큰 것이 있었다.** 고정비를 완화 하한(1,738,000)까지 미는 변형을 넷 더 재 보니
   차량 40 → 31 로 가는 동안 고정비 −405,000 에 **미배정 +1,160,000**, 사유는 **전부 위험물**.
   빈 좌석은 낭비가 아니라 **희소 능력 수요가 나중에 앉을 자리**였다. 그래서 순서를 바꾼다 —
   **17번(희소 능력 좌석)이 먼저**이고, 그것이 풀리면 위 표를 다시 낸다. 만드는 법은 측정 문서
   §2·§4 에 남겼다.

   **17번이 들어갔고, 그 위에서 다시 쟀다** (2026-09-12,
   [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) ·
   [측정](benchmarks/phase4-cluster-axis.md) §8). 위 네 조합은 전부 옛 기준의 것이고, 새 표는 §8 에
   있다. **묻는 것이 달라졌다** — `small` 격차는 17번이 닫았으므로, 이 측정의 질문은
   「`small` 을 얼마나 벌어 주나」가 아니라 **「C 의 손해가 어느 항에서 오는가」**다.

   - **C(§2 레시피)의 손해는 축 때문이 아니라 「차량 수 상한을 없앤 것」 때문이다.** C 는 세
     가지를 한꺼번에 바꿨고(축 · 가장 작은 차량급 · 상한 제거), 그 결과 클러스터가 차량보다
     많아진다(`large` 42 > 40, `peak` **121 > 88**). 남는 클러스터가 이미 실은 차에 얹혀 부챗살
     여럿을 오간다 — `peak` 권역 교차 293 → 392, 거리 +460 km, 합 **+1,507,476**.
   - **최소 정정(C′ — `targetClusters` 에 stop 축만 더한다)은 네 데이터셋을 모두 이긴다.**
     1,088,253 (−28.94%) · 3,824,655 (−17.51%) · 8,223,562 (**−15.07%**) · 21,484,730 (−24.94%),
     미배정 0·0·0·1. 클러스터 4·15·33·76 으로 전부 차량 수 안이다. §1.1 의 「축을 고치면」
     열(4·15·31·71)이 가리킨 것이 이것이었다.
   - **C′ 의 손해는 한 항뿐이고, 세 번째로 같은 자다.** `large` 에서 거리 −51,772 · 시간 −53,430 ·
     지각 stop 24 → **11** 인데 `priority-boost` 가 **+64,018**(우선 stop 477건 그대로, 라우트
     40대·평균 91.4 stop 그대로). 그 항이 없었다면 −15.73% 다.
   - **B 의 나쁜 반쪽은 「빈 차 우선 제거」다**(`peak` 미배정 **70**). 설계에 없는 것은 그 하나이고
     — §6.5 3단계에는 분할이 있다 — 측정으로 들어온 그 휴리스틱은 좌석 예약 뒤에도 값을 한다.
     **설계서에 적어 올려야 한다**(코드가 옳고 설계서가 불완전한 쪽이다).

   **판정: C′ 채택** (2026-09-12, [ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md) ·
   [측정](benchmarks/phase4-cluster-axis.md) §9). 위 표는 전부 **순번 자**의 것이고, 판정은
   [ADR-040](adr/ADR-040-priority-boost-decays-in-time.md) 이 자를 바꾼 **뒤에** 다시 내서 냈다 —
   순서가 반대였다면 「자를 바꿔서 이긴 것」이 됐을 것이다. 결론은 같다.

   | 데이터셋 | A (전) | **C′ (후)** | 차이 |
   |---|---:|---:|---:|
   | `small` | 1,120,725 (−26.15%) | **1,113,911 (−26.60%)** | −6,814 |
   | `medium` | 3,848,378 (−16.12%) | **3,791,148 (−17.37%)** | −57,230 |
   | `large` | 8,182,826 (−14.56%) | **8,147,294 (−14.93%)** | −35,532 |
   | `peak` | 21,739,077 (−23.37%) | **21,509,847 (−24.18%)** | −229,230 |

   `large` 는 **DoD 까지 6,353원**이고 거리가 1,486,291 → **1,404,499 m**(−81,792) 다.
   `medium` 은 차량을 **20 → 18대**로 줄이면서 −17.37% 가 됐다.

   **대가**: 개선 단계가 없으면(FAST) `medium` +48,761 · `large` +233,085 · `peak` **+1,311,428**
   이고 `peak` 미배정이 30 → 45 다. 클러스터가 상한에 붙어 있으면 배정에 여유가 없다.
   [ADR-034](adr/ADR-034-degrade-mode.md) 사다리의 첫 단이 무엇을 포기하는지에 대한 새 수치이므로
   **4-5 가 사다리를 다시 낸다.**

   **넣지 않은 것 둘**: 「가장 작은 차량급 기준」(묶어서 재지 않으려고 남겼다)과 「이분법 제거」
   (클러스터러가 고쳐진 뒤에도 상한을 넘은 클러스터의 안전망이다). 둘 다 따로 잰다.

   **「빈 차를 먼저 본다」는 설계서 §6.5 3단계에 올렸다** (ADR-041 §3) — 다만 그 수치는 좌석
   예약([ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md)) 위에서 잰 것이라는 단서를
   함께 달았다.

   **같은 작업에서 커밋한 것 둘**: `HardRule.routeStopCap()`(룰이 자기 stop 상한을 스스로 말한다 —
   16-c 의 `positionIndependent()` 와 같은 모양)과 벤치마크 리포트의 **고정비 하한 열**.

   게이트 데이터셋은 `medium` 으로 **둔다**. §6.9 의 근거는 결과가 아니라 자유도의 유무이고,
   결과를 보고 기준을 옮기면 다음 번에도 그렇게 된다.

   **처음에는 "차량 수 ≤ k 면 스윕 생략" 이라고 적었고, 그것은 원인을 잘못 짚은 것이다**
   (2026-09-05 정정). 수치가 그 설명을 넘어선다: 거리가 **336 km → 395 km 로 +17%** 인데,
   차 한 대를 더 쓴 것만으로는 그 폭이 나오지 않는다. 각도로 자른 쐐기는 depot 에서 **방사형으로
   길어지고**, 부록 A 의 밀도가 균일하지 않으므로(가우시안 혼합) **쐐기 하나가 먼 꼬리를 통째로
   떠안는다.** 즉 지는 원인은 "차량이 적어서" 가 아니라 **클러스터 수를 각도 자르기의 부산물로
   정한다**는 데 있다. 크기 조건은 그 증상이 나타나는 구간을 가릴 뿐이고, 비용 결정으로 바꾸는
   것이 메커니즘에 맞는 수정이다.

   1번의 inter-route relocate 가 거리 쪽은 일부 회수하겠지만 **다섯째 차의 고정비는 국소 탐색이
   없애지 못한다**(라우트를 비우는 이동은 relocate 의 이웃 안에 거의 없다). 그래서 이 항목은
   1번이 끝난 뒤에도 살아 있을 수 있고, 그때 고칠 곳은 시퀀싱이 아니라 2단계다.

   전략을 여러 개 돌려 보고 이긴 것을 고르는 **best-of-N 메타 전략은 게이트를 통과시키는 방법이
   아니다** — 그것은 게이트가 재려는 것을 재지 못하게 만든다.

9. **테스트 격리 — 픽스처가 정하지 않은 축이 여섯 있다.** 공통점은 하나다: 지금 깨지지 않는
   이유가 *테스트에 적혀 있지 않다.* 목록과 각각이 드러난 경위는 `docs/DESIGN.md` §13 의
   표가 기준이다 — Phase 3 마감에서 셋이던 것이 이 PR 하나에서 여섯이 됐다.

   - **시드 행** — `DispatchAdminIT` 가 시드(룰·차량·기사)를 직접 고치고 `@AfterEach` 로
     되돌린다. 되돌리기는 병렬 실행이 들어오면 무너지는 격리다. 캠프 하나를 이 테스트만의
     것으로 만들어 그 아래에 차량·기사·룰을 넣는 쪽으로 옮긴다.
   - **시각** (2026-09-05 에 실제로 깨졌다) — dispatch IT 셋이 약속 창을 `Instant.now()` 로
     만들어, 21시에 돌리면 근무창(06:00–22:00 KST) 밖이 되고 배정이 사라진다. 재배정은
     "복귀가 근무 종료 − 30분 버퍼" 를 넘겨 `ConflictException` 이 됐다. `PlanningClock` 을
     꺼내 셋이 함께 쓴다 — **옮기되 멈추지 않는다**(`Clock.fixed` 면 `planDurationMs` 가 0 이
     되어 "30초 이하" 가 언제나 참이 된다).

     **세어 봤다 (2026-09-07, 4-0).** 하드코딩된 절대 시각 자체는 결함이 아니다 — DB 를 왕복만
     하는 픽스처는 언제 돌려도 같다. 폭탄은 **운영 코드가 그 값을 벽시계와 비교할 때**이고,
     그런 비교 지점은 셋뿐이다: `FcSelection.isStale`(컷오프 24시간 상한, ADR-020 후속 정정),
     `CloseDueWavesService.closeDue`(`now − grace`), `RecoverStalePlansService`. 뒤의 둘은
     시각이 흐를수록 *더* 대상이 되므로 폭탄이 아니다. 남는 것은 `isStale` 하나이고, 거기에
     리터럴 컷오프를 먹이는 IT 는 `GeoFallbackIT` 하나였다 — **2026-09-07 에 실제로 터졌다**
     (열 캠프 전부 배차 불가, 실패 메시지는 캠프 코드 열 줄이라 원인을 말하지 않았다).
     컷오프를 주입된 `Clock` 에서 뽑고 "이 주문은 STALE 이 아니다" 를 첫 어설션으로 넣어 고쳤다.
     옆의 `GeoEquivalenceIT` 는 같은 리터럴을 갖고도 안전하다 — 자기 `FcSelection` 을
     `Clock.fixed` 로 만들어 쓰기 때문이다. `FulfillmentPublishIT` 는 `Instant.now()` 라 상하지
     않는다(다만 벽시계에 매인 픽스처라는 점은 남는다).
   - **릴레이 리더** (같은 날) — 한 DB 를 공유하는 IT 컨텍스트들 중 릴레이는 하나만 리더가
     된다(4-0). 먼저 뜬 클래스가 락을 쥐면 발행을 보는 IT 는 팔로워가 되어 아무것도 못 본다.
     발행을 보지 않는 IT 의 릴레이를 끄고, 보는 IT 에는 "이 컨텍스트가 리더다" 를 전제
     어설션으로 넣었다. **남은 곳**: order 는 발행 IT 가 하나뿐이라 지금은 성립하지만 그 사실이
     어디에도 적혀 있지 않고, fulfillment 는 발행에 의존하는 IT 가 **둘**이다
     (`FulfillmentPublishIT`·`WaveLifecycleIT`) — 통과하고 있지만 근거가 순서다.
   - **배정 동률** (2026-09-08, ✅ 4-0 에서 고침, [ADR-031](adr/ADR-031-least-capable-first-tie-break.md)) —
     한계비용이 같을 때 `GreedyAssigner` 가 `ORDER BY code` 순서로 차를 골랐다. 시드의 캠프별
     첫 차량이 냉장이라 작은 웨이브를 한 대가 흡수하면 **언제나 냉장차**가 뽑혔고,
     `make demo` 의 cold-chain 공허성 검사가 시각(남은 근무 시간이 짧으면 두 대가 필요해져
     통과)과 시드 배분에 따라 갈렸다 — **CI 의 이전 통과는 시각 운이었다.** 동률을
     「능력이 적은 차 먼저」로 채웠다. 위험물은 같은 논리로 넣었다가 **재 보고 뺐다**
     (미배정 99→115, 비용 +3.9% — `phase3-baseline.md` §4-7).
   - **플래너 통계** (2026-09-07 에 CI 에서 깨졌다, ✅ 4-0 에서 고침) —
     `DispatchPersistenceIT.계획_대상_조회가_인덱스를_탄다` 가 50행만 넣고 `ix_cand_wave` 를
     기대했다. 통계가 없는 테이블(`reltuples = -1`)에서 플래너는 기본 추정치로 **짐작하며
     인덱스를 고르므로** 테스트는 통과했고, autoanalyze 가 먼저 도는 CI 에서만 순차 스캔이 나와
     깨졌다 — 그리고 50행에서 순차 스캔은 **옳은 판단이다**. 즉 이 테스트는 인덱스가 아니라
     "플래너가 이 테이블을 아직 본 적 없음" 을 검사하고 있었다. 20,000행까지 채우고 `ANALYZE`
     한 뒤, 전제(통계 있음 · 교차점 400행 위)를 첫 어설션으로 말하게 고쳤다. 측정은
     `docs/benchmarks/phase4-dispatch-candidates-index.md`. **넷 중 이 하나만 빠져 있었다** —
     `FulfillmentPersistenceIT`·`FulfillmentRetentionIT`·`ProcessedEventRetentionIT` 는 이미
     크기와 `ANALYZE` 를 갖추고 있었다.

10·11 + 재삽입. **미배정 정책 — ✅ 완료** (2026-09-09, [ADR-028](adr/ADR-028-unassigned-policy.md) ·
    [측정](benchmarks/phase4-unassigned-policy.md)). 셋을 **하나의 결정**으로 묶었다 — 10 은 11 없이
    아무것도 가르지 못하고(운영 후보가 전부 우선도 0), 재삽입은 10 의 순서를 거꾸로 적용하는 것이다.
    따로 하면 같은 정책이 세 ADR 로 쪼개진다.

    - **11 — 우선도는 파생이다.** `promiseRevised`(+2) · `requiresCold`(+1) 에 점수표를 적용해
      **적재 시점**에 계산한다. **계약 변경 없음** — 두 사실 모두 `fulfillment.planned.v1` 에 이미
      있다. 점수표는 설정(`dawnline.dispatch.priority.*`), 근거는 DB 에 함께 남긴다(V4).
      「재배송 +3」은 사실을 tracking 이 만드는 **Phase 5** 로 미뤘다 — 사실 없는 가중치는 다음
      사람이 측정 없이 믿는 값이 된다.
    - **10 + 재삽입 — 「비싼 것부터 자리를 준다」.** 페널티 내림차순으로 최소 Δ비용 자리를 찾고
      **Δ비용 < 페널티일 때만** 싣는다(§6.1 그대로). "누가 빠지는가" 는 *끝까지 자리를 못 찾은 쪽*
      이다. 탐욕 뒤 한 번, **국소 탐색 뒤 한 번 더** — 개선 단계가 자리를 만들기 때문이다.
      재삽입은 개선이 아니라 값싼 탐욕이라 **FAST 모드에서도 돈다**(4번의 전제가 여기서 정해졌다).

    | 데이터셋 | `baseline-nn` | `sweep-greedy-nn` | `sweep-greedy-nn+ls` | 미배정 |
    |---|---:|---:|---:|---|
    | small | 1,510,366 | 1,642,762 | **1,451,439 (−3.90%)** | 9 → 9 |
    | medium | 5,032,092 | 4,553,620 | **4,162,954 (−17.27%)** | 14 → 13 |
    | large | 14,026,147 | 13,038,257 | **12,366,055 (−11.84%)** | 99 → 89 |

    > **재기준 주의**: 위 표는 14번 이전의 수치다. 현재 값은 [`phase4-constraint-classes.md`](benchmarks/phase4-constraint-classes.md) §4.

    **남은 89건이 왜 안 실리는지도 이 작업이 답했다 — 알고리즘이 아니다.** 재삽입 실패 stop
    18개를 전부 조사한 결과 **예산이 막은 것은 0건**이고 18개 전부 어느 라우트 어느 자리에도
    실을 수 없다. 전부 `냉장 ∧ 위험물` 인데 **데이터셋에 그 조합을 실을 차가 한 대뿐**이고
    (small·medium·large 모두 1대), **통합이 제약을 전파해**(§6.5 1단계) stop 하나가 평균 4.9건을
    데리고 미배정이 된다. 다음 결정은 아래 14번이다.

12. **`make demo` 와 CI 스모크가 하루 8시간 실패한다 — ✅ 완료** (2026-09-05 발견 · 2026-09-08 결정 **A** · [ADR-030](adr/ADR-030-night-shift-seed.md)).

    차량 시드의 근무창은 **06:00–22:00 KST** 하나뿐이고, §6.3 은 복귀가 `근무 종료 − 30분 버퍼`
    안이기를 요구한다. 그래서 21시 이후에 계획을 돌리면 실행 가능한 라우트가 하나도 없고 모든
    계획이 `NO_CANDIDATES` 로 끝난다. 2026-09-05 21:37 KST 에 실제로 그랬다 — 웨이브 29개가
    닫히고 후보 209건이 적재됐는데 계획 29개가 전부 FAILED 였다.

    **CI 에도 그대로 걸린다.** 러너는 UTC 이고 근무창은 **21:00–13:00 UTC** 다. 즉
    Compose 스모크(`make demo`)는 13:00–21:00 UTC 사이에 돌면 실패한다 — 하루 8시간. #34 에서
    통과한 것은 그 시각에 돌았기 때문이다. 꺼 둔 job 의 결함이 켜기 전까지 안 보였던 것과 같은
    종류이고, 이번에는 *켜져 있는데도* 시각에 따라만 보인다.

    당장 한 것: `phase3-demo.sh` 0단계가 근무창을 DB 에서 읽어 **전제로 확인하고**, 밖이면
    "PUBLISHED 계획 0" 이 아니라 원인을 말하며 멈춘다.

    **결정이 필요한 것은 그 다음이다.** 이 시드에는 **새벽 근무조가 없다** — 이 플랫폼의 이름이
    「당일·새벽 배송」인데 DAWN 티어(§부록 A)가 약속하는 시간대에 일하는 차량이 하나도 없다.
    후보 셋:

    | 안 | 내용 | 대가 |
    |---|---|---|
    | A | 시드에 **야간 근무조**를 넣는다(예: 캠프당 일부 차량 22:00–08:00) | 부록 A·`R__seed_dispatch` 변경. DAWN 티어가 처음으로 의미를 갖고 CI 창이 닫힌다. 벤치마크 절대 수치가 바뀌므로 baseline 재측정(§6.9 동결 규칙) |
    | B | 데모/CI 만 근무창 안에서 돌게 스케줄한다 | 시드는 그대로. CI 가 시각에 묶이고, "왜 이 시각인가" 를 아는 사람이 필요하다 |
    | C | 계획 시각을 주입 가능하게 한다(운영 경로에 테스트 훅) | 하지 않는 쪽에 가깝다 — 불변규칙 12 는 시계를 주입하라지 운영에 테스트용 시각을 넣으라는 것이 아니다 |

    **결정: A.** 캠프당 야간 8대(23:00–08:00) · 주간 12대(09:00–22:00), 냉장·대형은 두 조에
    배분(야간 냉장 5·트럭 3, 주간 냉장 3·트럭 3). C 는 테스트가 모델의 구멍을 가리는 방식이라
    기각, B(기사 단위 로스터)는 실제 운영의 방향이지만 모델 변경이라 **다음 단계**로 기록.

    **시드만으로는 성립하지 않았다** — 야간조는 자정을 넘으므로 둘이 따라왔다: ① 근무창을 날짜에
    붙이는 규칙(`JdbcReferenceData.shiftAt` — `end <= start` 면 다음 날, 그리고 어제·오늘 중
    *아직 끝나지 않은* 근무를 고른다), ② **근무 시작 전에는 출발하지 않는다**
    (`RouteState.empty`). ②가 없으면 `SHIFT_WINDOW` 는 복귀만 보므로 야간 차량이 "마감이 늦은
    주간 차량" 이 될 뿐이다.

    **이해 정정**: "DAWN 웨이브가 SHIFT_WINDOW 에 전부 걸린다" 는 지금 일어나는 일이 아니다 —
    `TimeWindowLimitRule` 은 지각만 보고(§6.5 조기 배송 허용), 데모는 컷오프까지 기다리지 않는다.
    CI 를 깨뜨린 것은 티어와 무관한 벽시계 문제였다. 다만 결론은 그대로다: 운영 의미대로 DAWN 이
    00:00 에 계획되면 출발 06:00 · 도착 상한 08:00 로 **웨이브 전체에 두 시간**이었다.

    **회귀 가드**: `DispatchSeedCoverageIT.어느_시각에_계획해도_출발할_수_있는_차량이_있다` 가
    24시간을 한 시간씩 돈다 — 데모를 특정 시각에 돌려 보는 것으로는 창이 닫혔다는 것을 증명할 수
    없다. 야간조를 빼면 **00:00–04:00 · 22:00–23:00 KST** 를 이름으로 대며 실패한다.

13. **계획 영속화 20초 — auto-flush 더티 체크. ✅ 완료** (2026-09-07 측정 · 2026-09-08 결정 **B+프로젝션** · [ADR-029](adr/ADR-029-optimizer-io-is-bulk-not-orm.md)).

    5,000건 왕복 26.8초 중 **20.2초(75%)가 라우트·설명 저장**이다. 원인은 DB 가 아니다:
    `JdbcPlannedRouteRepository` 는 행마다 네이티브 INSERT 를 돌리는데, Hibernate 는 네이티브
    질의 실행 전마다 영속성 컨텍스트를 auto-flush 하고, 그 시점 세션에는 `findPlannableInWave`
    가 올려 둔 **관리 엔티티 5,000개**가 떠 있다. flush 10,652회 × 엔티티 5,001개다.

    통제 실험으로 갈랐다 — 같은 5,000행 INSERT 를 빈 세션에서 **635 ms**, 후보 5,000개를 올린
    세션에서 **13,877 ms**, **21.9배**. 측정:
    `docs/benchmarks/phase4-plan-roundtrip-breakdown.md`.

    같은 측정에서 리뷰가 물은 두 구간도 따로 냈다. 둘 다 그 25초 **안에 없었다**(후보는 시드로
    넣고 릴레이는 꺼져 있다): 소비자 처리량 **1,638 건/초**(§8.2 피크 600 rps 의 2.7배),
    릴레이 지연 **111 ms**(폴링 주기 한 번). 예상과 달리 후보 5,000건의 상태 반영은 **18 ms**
    다 — 1차 캐시가 답한다.

    **결정: B — 그리고 B 만으로는 절반이다.** 원인은 "쓰기가 느리다" 가 아니라 *읽지 않아도 되는
    엔티티 5,000개를 세션에 올려 둔 것*이므로 고칠 곳이 둘이다. ① 후보는 읽기 전용 프로젝션으로
    읽고 상태 반영은 결과별 집합 `UPDATE … WHERE order_id = ANY(?)`, ② 라우트·stop·설명은 JDBC
    배치 INSERT(`reWriteBatchedInserts=true`). A(`FlushMode.COMMIT`)는 기각 — 증상만 숨기고
    원인은 그대로이며 read-your-writes 위험을 설정 한 줄로 전역에 들인다(ADR-029).

    **결과** — 계획 결과는 한 자리도 바뀌지 않았다(라우트 20 · 배정 3,810 · 미배정 1,190 ·
    비용 39,102,032원).

    | | 이전 | 이후 |
    |---|---:|---:|
    | 왕복 | 26,750 ms | **5,572 ms** |
    | 알고리즘 | 5,030 ms | 4,730 ms |
    | **영속화** | ~20,400 ms | **800 ms** (25배) |
    | flush 횟수 | 10,652 | **22** |
    | 세션 적재 엔티티 | 5,001 | **1** |

    §7.1 에 원칙 한 줄(**최적화기 I/O 경로는 ORM 이 아니라 벌크**), §9.1 에
    `dawnline_plan_persist_seconds`, §6.7 에 목표 **5,000건 영속화 ≤ 3초** 를 넣었고
    `PhaseThreeDoDIT` 가 그 목표를 게이트로 어설션한다 — 문서가 아니라 게이트다.
    **(2026-09-24 정정)** 그 게이트는 CI 러너의 시간이라 §6.9 규칙 2 의 예외였다 — 같은 코드가
    789–2,919 ms 로 흔들리다 3,013 ms 로 넘었다. 게이트를 flush 횟수·세션 적재 엔티티로 옮겼고
    시간은 기록만 한다([ADR-029 후속 정정](adr/ADR-029-optimizer-io-is-bulk-not-orm.md)).

14. **겹친 제약은 한 대에 몰리지 않는다 — ✅ 완료** (2026-09-09 발견 · 결정 **B → 데이터셋 → 재기준 → A → 재기준** · [ADR-033](adr/ADR-033-constraint-classes.md) · [측정](benchmarks/phase4-constraint-classes.md)).

    10·11 측정의 부산물로 드러났다: `large` 총비용의 **34%가 누구도 실을 수 없는 상수**였다.
    「데이터로 테스트를 통과시키기」와의 구별은 **무엇을 고치는가 — 측정 대상인가 도구인가**이고,
    두 질문으로 갈랐다(둘 다 «예» 면 도구의 결함이다):

    1. 실패한 부분이 **어떤 알고리즘으로도** 실패하는가 — 18 stop 은 모든 라우트 × 모든 자리에서 실행 불가. **예**
    2. 데이터셋이 **자기가 선언한 전제**를 어기는가 — 생성기의 첨자 산술이 냉장∧위험물 차량을
       크기와 무관하게 1대로 만든다(§6.7 「정상 용량」·부록 A 능력 분포). **예**

    **순서와 기록이 이 결정의 정당성 전부다.** 기준을 먼저 쓰고(제약 조합별 80%, `DatasetFeasibilityTest`),
    실패를 눈으로 보고(`large 냉장∧위험물 110%`), 고치고, 세 열로 남겼다.

    | 데이터셋 (`+ls` vs `baseline-nn`) | 정정 전 | B(데이터셋) 후 | **A(통합 키) 후** |
    |---|---:|---:|---:|
    | small | −3.90% | −3.90% | **−2.68%** |
    | medium | −17.27% | −27.57% | **−15.47%** |
    | large | −11.84% | −43.98% | **−14.53%** |

    **가운데 열이 요점이다.** B 만으로 −43.98% 가 나왔지만 그건 자가 반대로 휜 것이다 —
    `baseline-nn` 은 차량을 순서대로 꺼내 늘어난 위험물 차량을 못 썼고 미배정이 148 → **174**
    로 늘었다. A 가 그 휨을 폈다(통합의 제약 전파는 베이스라인이 가장 크게 당하던 결함이라
    미배정 174 → 63). 남은 **−14.53%** 가 지금 이 알고리즘의 실제 위치다.

    부록 A 에도 같은 규칙을 넣었다 — 위험물 허용 20%(캠프당 4/20), 그중 절반이 냉장,
    **조마다 냉장∧위험물 1대씩**. `DispatchSeedCoverageIT` 가 캠프별·조별로 확인한다.

    **재검토 지점**: 필요할 때만 분할(split-on-demand)은 stop 수 압박이 관측되면 검토한다.

**DoD**
- `large`에서 `sweep-greedy-nn+ls`가 `baseline-nn` 대비 총비용 ≥ 15% 절감(미달 시 튜닝 기록과 실제 수치 보고).
  - **2026-09-12 현재 −14.93%** (8,147,294 / 9,577,578 — **6,353원 부족**). **다섯 번 재기준된
    수치다** — −11.84%(측정 도구가 용량 부족을 재던 상태) → −43.98%(도구를 고쳤으나 자가 반대로
    휜 상태) → −14.53%(모델까지 고친 상태, 세 열은
    [`phase4-constraint-classes.md`](benchmarks/phase4-constraint-classes.md) §4) →
    −14.47%(좌석 예약, [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md)) →
    −14.56%(자를 바꾼 뒤, [ADR-040](adr/ADR-040-priority-boost-decays-in-time.md) ·
    [측정](benchmarks/phase4-priority-boost.md) §8) →
    **−14.93%**(클러스터의 축, [ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md) ·
    [측정](benchmarks/phase4-cluster-axis.md) §9).
    `medium` 은 **−17.37%** 로 통과한다. 튜닝 기록은
    [`phase4-local-search.md`](benchmarks/phase4-local-search.md) §3.
  - **2026-09-17 — 넘었다. −19.30% → (4-19 뒤) −18.20%. 다만 그것을 낸 전략은 기본 전략이 아니다.**
    §6.6 표에 처음부터 있던 비교 전략 `savings-cw+ls`(4-2,
    [ADR-042](adr/ADR-042-savings-merges-are-class-aware.md) ·
    [측정](benchmarks/phase4-savings-cw.md))가 `large` 에서 **7,729,298 / 9,577,578 = −19.30%**
    를 수렴으로 냈다. **6,353원을 쫓지 않기로 하고 다음 항목으로 간 결과**이고, 넘긴 것은 자도
    축도 아니라 **라우트를 만드는 방법**이다.
    **기본 전략(`sweep-greedy-nn+ls`)은 여전히 −14.93%** 이고, 바꾸지 않은 이유와 바꾸는 조건은
    [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) 에 있다 — `peak` 에서
    30초 예산에 잘리고 **잘린 값이 실행마다 다르다**(20,940,782 ~ 23,277,318).
    **이 줄은 두 수를 함께 적는다** — 하나만 읽으면 다음 사람이 잘못 읽는다.
    **그리고 4-19 뒤에 −18.20% 로 내려갔다**([ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) ·
    [측정](benchmarks/phase4-endpoint-merges.md) §5) — 같은 변경이 `peak` 을 수렴시켰고 `large` 를
    105,502원 비싸게 만들었다. 내려간 것도 그 열에 적는다.
- 5,000 주문 계획 p95 ≤ 30초(미달 시 프로파일링 결과·병목 문서화).
  - **통과 — 5,829 ms** (여유 5배). Phase 3 대비 탐욕 단계가 15배 빨라진 것이 함께 들어 있다.
- 계획 시간 예산을 5초로 줄였을 때 FAST 모드로 전환되고 결과가 여전히 하드 룰을 만족.
  - **통과 — 다만 이 줄의 「FAST」는 정정 전 규칙을 가리킨다** ([측정](benchmarks/phase4-fast-mode.md) §7).
  - **정정 전 규칙에서 측정된 사실**(그대로 남긴다): `--budget-seconds 5` 로 `large` 를 돌리면
    FULL 이 **4,898 ms** — [ADR-032](adr/ADR-032-local-search-budget-and-approximations.md) 의 패스
    단위 예산이 개선 단계를 끊은 값이고 잘린 대가는 **+0.21%** 다. 그 4,898 ms 는 예산의 98% 라
    임계 80%(4,000 ms)를 넘고, 원래 규칙에서는 다음 계획이 **FAST** 였다 — FAST 는 **1,542 ms**
    (예산의 31%), 하드 룰 5회 전부 통과.
  - **정정 후**([ADR-034](adr/ADR-034-degrade-mode.md) 후속 정정): 그 조건의 처방은 FAST 가 아니라
    **개선 예산 × 0.5** 다. DoD 가 요구한 성질 — *예산이 조이면 열화가 일어나고 하드 룰은 그대로다* —
    는 **아랫단이 만족시킨다**: **3,208 ms**(예산의 64%) · 대가 **+1.35%** · 하드 룰 5회 통과 ·
    미배정 1 → 0. FAST 는 랙이 있을 때 그 위에 더 있다. 전이는 `PlanModeSelectorTest`·
    `RunPlanServiceTest` 가 확인하고, 하드 룰은 `BenchmarkRunner` 가 회차마다 돌린다.
  - **정정 둘째**: 이전에 여기 적혀 있던 "`sweep-greedy-nn` 이 그 모드의 기본이다" 는 틀렸다.
    FAST 는 **전략을 바꾸지 않는다** — 같은 전략의 §6.5 5단계를 끄는 것이고, 계획 행의 전략
    이름은 `sweep-greedy-nn+ls` 로 남는다.
  - **2026-09-18 재측정** (4-5, [측정](benchmarks/phase4-strategies.md) §4): ADR-039·040·041 이
    개선 단계의 일을 줄여서 **5초 예산에서는 아랫단 조건이 켜지지 않는다**(`large` FULL 3,453 ms
    = 예산의 69%). 켜지는 첫 예산은 **4초**이고 대가는 **+1.06%**, 하드 룰 5회 통과다 — DoD 가
    요구한 성질(*예산이 조이면 열화가 일어나고 하드 룰은 그대로*)은 그대로 성립하고 **자리만
    옮겨졌다.** 윗단의 대가는 **전략마다 다르다**(`large` 기본 +13.08% 대 savings +4.29%).
    그리고 **다섯 데이터셋 전부에서 기본 전략의 FAST 결과가 `sweep-greedy-nn` 의 FULL 과 한
    자리도 다르지 않다** — 「전략을 바꾸지 않는다」의 다섯 번째 확인이다.
- **벤치마크 리포트**(4-5) — ✅ [`phase4-strategies.md`](benchmarks/phase4-strategies.md).
  DoD 대조는 그 문서 §8, Phase 작업 대조는 아래 **마감 대조표**.

15. **안 훑기 — 라우트 사이 스캔의 97.6%가 헛스캔이다** (2026-09-11 측정, [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) 5번 · [측정](benchmarks/phase4-where-the-time-is.md)).

    `large` 의 개선 단계는 9패스 동안 위치를 **33,045번 훑어 이동을 793번** 찾는다. 마지막 패스는
    3,653번 훑어 **7번**이다. 그런데 패스 시간은 564 → 370 ms 로 1.5배밖에 안 줄어든다 —
    **패스 비용이 「찾은 이동」이 아니라 「훑은 위치」에 붙어 있기** 때문이다.

    | 패스 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 합 |
    |---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
    | 스캔 | 3,673 | 3,721 | 3,682 | 3,672 | 3,670 | 3,661 | 3,661 | 3,652 | 3,653 | 33,045 |
    | 이동 | 332 | 190 | 94 | 69 | 47 | 27 | 19 | 8 | **7** | 793 |

    **처방은 라우트 쌍 단위 dirty 플래그다.** relocate·swap 의 Δ비용은 두 라우트의 상태에만
    의존하므로, **지난 스캔 이후 둘 다 안 바뀐 쌍은 「개선 없음」이 그대로 참**이다.

    **조건은 정확(exact)이다.** 결과가 전체 스캔과 **한 자리도 달라선 안 되고**, 그것을
    어설션한다. first-improvement 순서도 보존된다 — 건너뛴 쌍에는 원래 개선이 없었으므로 «앞선
    개선을 놓치고 뒤엣것을 집는» 일이 생기지 않는다. 노드 단위 don't-look bits 처럼 근사가 섞이면
    그건 알고리즘 변경이라 **재기준 경로**로 가야 하고, 별도 커밋이다.

    동일성 테스트는 **「이 실행은 수렴으로 끝났다」를 전제 어설션으로 먼저 말한다** — 예산이 물려
    끊긴 실행은 기계가 다르면 잘리는 지점이 달라 애초에 재현 대상이 아니다(ADR-035 4번).

    **상한을 그림자로 쟀다 — 4.0 ~ 17.2%** (2026-09-11, [측정](benchmarks/phase4-peak-gate.md) §3).
    판정은 계산하되 실제로 건너뛰지 않고, 건너뛸 수 있었던 쌍에서 개선 이동이 나오는지도 함께 봤다.

    | | `small`(5대) | `medium`(20대) | `large`(40대) | `peak`(60대) |
    |---|---:|---:|---:|---:|
    | 건너뛸 수 있던 쌍 | 13.2% | 4.0% | **17.2%** | 2.0% |
    | 잘못된 건너뛰기 | 0 | 0 | 0 | 0 |

    **이론은 맞다**(falseSkips = 0 — 정확한 규칙이다). **그런데 97.6%의 낭비 중 회수되는 것은
    4~17%뿐이다.** 이유는 한 줄이다 — **무효화의 단위는 라우트 전체(91~140 stop)인데 낭비의
    단위는 위치 하나다.** 이동 하나가 라우트 둘을 통째로 더럽히고, 라우트는 40~60개뿐이라
    마지막 패스의 이동 7건만으로도 쌍의 절반이 무효가 된다. 수확은 뒷패스에 몰려 있고,
    `peak` 처럼 예산이 물면 뒷패스에 닿지도 못한다(그래서 2.0%).

    시간 환산 상한은 `large` 633 ms(계획의 11%) · `peak` 257 ms(0.6%)다.

    **결정: 만들지 않는다 — ⛔ 종료** (2026-09-12). 병렬화(3번)와 같은 부류의 기록이다:
    **«측정했고, 만들 이유가 측정되지 않았다.»** 위치 단위 무효화는 근사가 섞이는 알고리즘
    변경이라 지금 열지 않는다(재기준 경로). 재검토 조건은 측정 문서 §3 에 있다.

    이 항목이 남긴 것은 기계장치가 아니라 **방법**이다 — 「최적화 항목은 구현 전에 그림자
    계측으로 상한을 잰다」가 §6.9 의 규칙이 됐다.

    > 사용자가 이 항목을 **4-14** 로 불렀는데 14번은 이미 「겹친 제약」이 쓰고 있어 **15번**으로 둔다.

16. **`peak` 이 드러낸 셋 — 재삽입 61% · 예산은 계획을 묶지 않는다 · 데이터셋이 구조적으로 불가능하다**
    (2026-09-11 발견, [측정](benchmarks/phase4-peak-gate.md) §2. **[결정 필요]**).

    | `peak` 43.2초 | 시간 | 비중 |
    |---|---:|---:|
    | 3 탐욕 배정 | 3,044 ms | 7.0% |
    | **재삽입 1회차** | **13,085 ms** | **30.3%** |
    | 5 개선 (예산 안) | 12,859 ms | 29.7% |
    | **재삽입 2회차** | **13,459 ms** | **31.1%** |

    - **재삽입이 61.4%다.** `large` 에서는 0.6%(34 ms)였다. 미배정 stop 하나마다 모든 라우트에
      끼워 넣어 보므로 미배정이 많을수록 제곱으로 커진다 — `peak` 은 미배정이 3,521건이다.
    - **30초 예산은 계획을 묶지 않는다.** 개선 단계는 예산을 정확히 지켰는데(13,841 ms 를 받아
      12,859 ms 사용) 계획은 43.2초다. 예산은 §6.5 5단계의 마감일 뿐 배정·재삽입에는 걸려 있지 않다.
    - **`peak` 은 구조적으로 불가능하다.** `max-stops` 120 × 차량 60 = stop 7,200 인데 통합 후
      stop 은 8,411 이다 — **최소 1,211건(14.4%)은 어떤 알고리즘으로도 못 싣는다.** 미배정 3,521건
      중 3,426건의 사유가 `max-stops` 이고, 총비용의 88%가 미배정 페널티다.
      [ADR-033](adr/ADR-033-constraint-classes.md) 이 `large` 에서 잡은 것과 같은 형태다.

    **셋은 하나의 뿌리다 — 과부하.** 2026-09-12 결정: **16-a 도구 → 16-b 마감 → 16-c 가지치기**.
    재기준 규칙은 [ADR-033](adr/ADR-033-constraint-classes.md) 과 같다 — 도구 변경(16-a)은
    **전후 열**, 정확한 변경(16-b·c)은 **동일성 어설션**, 휴리스틱은 **재기준**.

### 16-a. 데이터셋을 둘로 — ✅ 완료 (2026-09-12)

    실현 가능성 기준을 **stop 수 축**으로 확장했다: **통합 후 stop ≤ 0.8 × max-stops(120) ×
    차량 수**. [ADR-033](adr/ADR-033-constraint-classes.md) 의 80% 를 중량·부피에서 stop 축으로
    옮긴 것뿐이다 — 그 축만 여유가 0%(「차량 수 × 120 이하」)였고, 그건 완벽한 패킹을 요구하는
    수였다. 검사 목록도 **드는 방식에서 빼는 방식(`EXCLUDE`)으로** 바꿨다. 드는 방식이었기에
    `peak` 이 목록에 없었고, 그래서 아무도 8,411 > 7,200 을 보지 못했다.

    | | 주문 | 차량 | 통합 후 stop | 슬롯의 | 미배정 | 계획 시간 | 총비용 |
    |---|---:|---:|---:|---:|---:|---:|---:|
    | 옛 `peak` → **`overload`** | 15,000 | 60 | 8,411 | **146%** | 3,521 (23.5%) | 43.2초 | 147,956,375 |
    | 새 **`peak`** | 15,000 | **88** | 8,411 | **80%** | **10 (0.067%)** | **20.6초** | 22,341,428 |

    차량 88대는 고른 값이 아니라 **기준이 정한 최소 대수**다(8,411 ≤ 0.8 × 120 × V). 둘은
    주문·seed 가 같아 **차이가 오직 대수뿐**이고, 그래서 둘을 나란히 두면 「용량이 모자라면
    무슨 일이 일어나는가」가 분리되어 보인다.

    새 `peak` 은 §6.7 의 두 목표를 **처음으로 함께** 통과한다 — 미배정률 0.067%(≤ 0.5%)와
    계획 시간 20.6초(≤ 30초). 미배정 페널티가 총비용의 88% → **1.4%** 로 내려와 이제 표가
    라우팅을 잰다.

    `overload` 는 버리지 않는다. 재는 것이 셋이다 — 미배정 정책(ADR-028) · 계획 시간의 상한 ·
    열화(§6.7). 다만 **웨이브는 (캠프, 티어, 컷오프) 단위**이므로 15,000건 한 웨이브는
    **캠프 하루치를 통째로 한 웨이브에 넣은 것**이고, 「성수기의 정상 부하」로 읽으면 안 된다.
    §6.9 비교표는 두 절로 나눈다. 게이트는 `medium` 그대로.

### 16-b. 마감은 계획 전체의 것이다 — ⏳

    §6.7 의 `PlanningBudget(totalMs, …)` 는 계획의 예산으로 썼는데 구현은 개선 단계에만
    걸었다 — **설계 부합 수정이지 재기준이 아니다.** 배정·재삽입도 같은 마감 아래 두고,
    마감이 오면 남은 미배정은 미배정으로 끝낸다. 실현 가능한 데이터셋에서는 마감이 물지
    않는다는 **수렴 전제 어설션**으로 동일성을 유지한다.

    이것이 없으면 **열화 사다리 전체가 허구다** — `overload` 에서 재삽입이 61%이므로
    **FAST 가 개선을 꺼도 43초는 30초가 되지 않는다.** ADR-028 의 「재삽입은 값싼 탐욕」은
    *실행 가능한 문제에서만* 참이었고, 그것을 참으로 만드는 것이 마감이다.

### 16-c. 재삽입의 정확한 가지치기 — ✅ 완료 (2026-09-12, [ADR-037](adr/ADR-037-reinsertion-prunes-what-cannot-fit.md) · [측정](benchmarks/phase4-repair-pruning.md))

    **§6.9 규칙대로 구현 전에 그림자로 상한을 쟀다**: `overload` **96.8%** · `large` 93.2%.
    4-15(안 훑기)의 4~17% 와 정반대이고, 이유도 대칭이다 — 안 훑기는 무효화 단위(라우트 전체)가
    낭비 단위(위치 하나)보다 컸고, 가지치기는 **판정 단위와 낭비 단위가 같다.**

    하드 룰을 「위치와 무관한가」로 갈랐다. stop 수·누적 적재·차량 속성은 **넣는 자리와
    무관**하므로(어디에 끼워도 `stopCount + 1`, 덧셈은 교환법칙) 그 룰이 거절한 라우트는
    어느 자리도 볼 필요가 없다. 근무창·약속창은 도착 시각을 보므로 대상이 아니다.
    **기본값이 「거짓」인 것은 의도다** — 새 룰이 조용히 대상이 되면 답이 달라진다.

    | 데이터셋 | 총비용 전 → 후 | 계획 시간 전 → 후 | 종료 |
    |---|---|---|---|
    | `small`·`medium`·`large`·`peak` | **한 자리도 안 바뀜** | 그대로 (`peak` 20.7 → 19.9초) | 수렴 |
    | `overload` | 163,608,882 → **146,736,007** | 30,014 → **19,211 ms** | 마감 → **수렴** |

    **`overload` 의 146,736,007 은 게이트에서 120초 예산으로 잰 수렴값과 정확히 같다.** 낭비를
    걷어내자 30초 안에서 끊기지 않고 같은 답에 닿았다 — 마감이 물지 않는 상한이 된 것이
    [ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md) 의 재검토 지점에 적어 둔 정상 상태다.
    미배정 사유도 `plan-deadline` 에서 `max-stops` 로 돌아와, 그 표가 다시 **용량 부족**을 말한다.

    여정: **43,233 ms**(마감 없음) → 30,014(마감에 잘림) → **19,211 ms**(가지치기, 수렴). 2.25배.

    동일성은 어설션으로 지킨다 — `PositionIndependenceTest` 가 정리(위치 무관 룰의 판정은
    자리와 무관하다)를 **모든 자리에 실제로 끼워 넣어** 확인하고, 반대 방향(자리가 남으면 막지
    않는다)도 함께 본다. k-최근접 라우트 한정은 **열지 않았다** — 정확한 가지치기로 충분했다.

17. ✅ **희소 능력 좌석 — 좌석은 능력이 아니라 제약 조합에 예약한다** (2026-09-12 완료,
    [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) ·
    [측정](benchmarks/phase4-scarce-seats.md)).

    8번이 고정비를 완화 하한까지 밀어 본 네 변형에서 **차량이 줄수록 미배정이 그보다 크게
    늘었고, 늘어난 사유는 전부 위험물**이었다 — `large` 에서 고정비 −405,000 대 미배정
    +1,160,000(2.9배), 하이브리드 변형은 4.0배. 같은 사실이 `small` 에도 있다: 총비용
    1,490,513 중 **미배정 페널티가 350,000원**이고 전부 위험물인데, `small` 의 위험물 차량은
    **5대 중 1대**다. 그래서 **`small` 은 8번으로 차는 줄어도 −15% 는 닫지 못한다** — 남은
    **188,755원**(= 1,490,513 − 1,531,480 × 0.85)은 클러스터 기하가 아니라 여기 있다.

    메커니즘은 셋이 겹친다. ① [ADR-031](adr/ADR-031-least-capable-first-tie-break.md) 의
    「능력이 적은 차 먼저」는 **동률에만** 걸리고 **셋째 키**다. ② 위험물은 그 순위에서
    **측정으로 빠져 있다**(2% 비율에서는 아껴 두기가 손해였다 — 미배정 99 → 115). ③ 배정의
    첫째 키가 `leftover` 라, 클러스터에 냉장 stop 이 섞이면(냉장 25% — 거의 모든 클러스터가
    그렇다) **비냉장 차가 구조적으로 진다.** `large` 에서 트럭이 stop 의 59%를 싣고 밴 15대가
    나머지만 받는 것이 그 결과다.

    **그림자 계측 완료** (2026-09-12, [측정](benchmarks/phase4-scarce-seats.md)). 셋 다 답이 나왔고,
    **진단이 한 칸 좁아졌다 — 희소한 것은 능력이 아니라 제약 조합이다.**

    - **ⓐ** 냉장 ∧ 위험물을 싣는 차량이 실은 stop 의 **94~100%가 일반 수요**다(`small` 은 1대의
      120 자리가 **전부** 일반 수요). 미배정 위험물은 거의 전부 그 조합이다 — `small` 4/9 ·
      `large` 1/1 · `peak` 9/9. **상한**(미배정 위험물 페널티)은 350,000 / 0 / 30,000 / 290,000원,
      좌석을 실제로 만들어 앉힌 **실현**은 **217,894 / 0 / −10,945 / 38,728원**이다.
      `small` 은 격차 188,755원을 넘고, **`large` 는 음수**다 — 그 1건은 한계비용 36,653원 >
      페널티 30,000원이라 §6.1 이 옳게 거절한 것이다. **이 항목의 단독 이득은 `small` 에 있다.**
    - **ⓑ** **좌석 예약**이다. 네 데이터셋에서 **클러스터의 100%가 위험물 stop 을 포함**하므로
      「희소 수요를 요구하는 클러스터를 먼저」는 완전한 no-op 이다. 예약의 단위는 **능력이 아니라
      제약 조합**([ADR-033](adr/ADR-033-constraint-classes.md)의 축) — `peak` 에서 위험물 차량의
      여유 슬롯은 287개인데 그 조합을 싣는 9대의 여유는 **5개**다. 능력별로 읽으면 「충분하다」가
      나오고 9건이 미배정으로 남는다.
    - **ⓒ** **걷힌다 — 키 순서를 바꾸지 않아도 된다.** 예약은 하드 용량이라 정렬 키 위에서
      성립한다. (키 자체의 문제는 남는다: 모든 클러스터가 두 희소 능력을 다 포함하므로 첫째 키가
      **구조적으로 가장 희소한 차를 가리킨다** — 위험물 stop 을 하나도 안 실은 위험물 차량이
      네 데이터셋 전부에서 0대다. 그것은 8·18번의 몫이고 한 커밋에 섞지 않는다.)

    **8번과 분리하되, 순서는 17 → 8 이다.** 8번(클러스터 기하)과 이 항목(좌석 배분)은 **둘 다
    「어느 stop 이 한 라우트를 공유하는가」를 바꾼다.** 한 커밋에 묶으면 어느 쪽이 수치를
    움직였는지 말할 수 없고, 따로 재면 각각 *상대가 뒤집을 상태*를 기준으로 재기준을 남긴다 —
    그래서 8번이 보류다. 보류의 근거는 `large` 의 1.65%p 가 아니라 이 **얽힘**이다
    ([ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) §4).

    **B(설계대로 배정)도 단독으로 넣지 않는다.** B 가 `large` 에서 −3.83% 였던 것은 구현의
    실패가 아니라 **§6.5 3단계 문장의 불완전**이다. 이 항목이 그 정정이고, 설계서의 표시는
    「좌석 예약으로 정정(ADR-039)」으로 바꿨다.

    **결과** (2026-09-12, [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) ·
    [재기준](benchmarks/phase4-scarce-seats.md) §8).

    | | `baseline-nn`(동결) | 전 | **후** | 변화 | 미배정 |
    |---|---:|---:|---:|---:|---|
    | `small` | 1,531,480 | 1,490,513 (−2.67%) | **1,136,026 (−25.82%)** | **−354,487** | 9 → **0** |
    | `medium` | 4,636,549 | 3,919,106 (−15.47%) | **3,893,515 (−16.03%)** | −25,591 | 0 → 0 |
    | `large` | 9,682,478 | 8,276,130 (−14.52%) | **8,281,646 (−14.47%)** | **+5,516** | 1 → **0** |
    | `peak` | 28,624,728 | 22,341,428 (−21.95%) | **21,774,900 (−23.93%)** | −566,528 | 10 → 1 |

    - **`small` 의 DoD 가 닫혔다** — 남은 188,755원을 여기서 걷었고 실제로는 354,487원이었다.
      `medium`·`peak` 도 −15% 위다. **`large` 만 51,540원 부족**하고, 진 자리는 예상대로
      `priority-boost`(−382,202 → −350,866, **+31,336**) — 4-8 의 `large` 가 졌던 것과 **같은 항**
      이다(**18번**).
    - **상한이 이번에는 아래쪽으로 틀렸다.** 그림자 계측의 「실현」은 217,894 / 0 / −10,945 /
      38,728 이었는데 실제는 354,487 / 25,591 / **−5,516** / 566,528 이다. 좌석 예약은 미배정만
      걷지 않고 **배정 자체를 바꾼다**(`peak` 지각 페널티 202,050 → 79,700원). §6.9 에 그 문장을
      보강했다 — **상한은 「그 항」의 상한이지 「그 변경」의 상한이 아니다.**
    - **다음은 8번이다.** 네 조합(현재/배정만/축만/둘 다)을 **이 결정 위에서** 다시 잰다.

    > 사용자가 이 항목을 **4-16** 으로 불렀는데 16번은 이미 「`peak` 이 드러낸 셋」이 쓰고 있어
    > **17번**으로 둔다. 15번이 같은 이유로 한 칸 밀린 것과 같다.

18. ✅ **`priority-boost` 의 `÷ position` 은 §6.3 의 정책 문장을 정직하게 재는가 — 룰 설계 검토**
    (2026-09-12 8번이 열었다 · 시점을 앞당겼다: 8번 측정 뒤, 리포트(5) 전 · **2026-09-12 결정:
    (b′) 채택, [ADR-040](adr/ADR-040-priority-boost-decays-in-time.md)**.
    [ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) §4 · [측정](benchmarks/phase4-cluster-axis.md) §4 ·
    [측정](benchmarks/phase4-scarce-seats.md) §8.2 · [측정](benchmarks/phase4-priority-boost.md)).

    소프트 룰 `PRIORITY_BOOST` 의 값은 `bonusKrw × priority ÷ position` 이다. `÷ position` 은
    라우트가 길어질수록 보너스가 줄어들므로 **구조적으로 「차를 더 쓰는」 계획에 유리한 자**다.
    라우트 수를 줄이는 **모든** 작업이 이 항에서 손해를 본다.

    **시점을 앞당긴 이유: 같은 항이 두 번 설명했고 세 번째를 가리킨다.**

    | 언제 | 무엇이 졌나 | `priority-boost` 기여 |
    |---|---|---:|
    | 4-8 C(축 정정) | `large` −14.52% → −12.95% | **+83,923** (우선 stop 477건 그대로, 자리만 뒤로) |
    | 4-17(좌석 예약) | `large` +5,516 | **+31,336** (역시 477건 그대로) |
    | 남은 표적 | `large` DoD 까지 **51,540원** | — |

    Phase 4 마감 뒤로 미루면 **발행 직후 재기준할 자로 리포트를 내는 것**이 된다. 리포트는
    우리가 변호할 자 위에 서야 한다. 그래서 순서는 **8 → 18 → C 판정 → 2 → 5** 다.

    **질문은 최적화가 아니라 정책이고, 정확히 이렇게 세운다.** §6.3 의 문장은 「우선 고객을 앞
    순서에 두면 보너스」다. 문제는 `÷ position` 이 그 정책의 **대리 변수**라는 것이다 — 순서
    인덱스는 시간을 근사하지만, **같은 시간 프로파일이라도 stop 수가 다르면 다른 보너스**를 주고,
    그래서 라우트를 잘게 쪼갠 계획이 구조적으로 보너스를 더 모은다. 선택지는 셋이다.

    - **(a) 지금 그대로** — 「이른 순서」가 정책이고, 차를 더 쓰는 비용을 `bonusKrw` 가 살 만하다고
      본다.
    - **(b) 시간 기준** — 약속창 시작 대비 **계획 도착 시각**으로 보너스. 라우트 길이에 중립이다.
    - **(c) 정규화** — `position ÷ 라우트 길이`.

    **그림자 계측 완료** (2026-09-12, [측정](benchmarks/phase4-priority-boost.md)). 같은 문제의 두
    계획(A 와 4-8 의 C′ — `large` 에서 라우트 40대·평균 91.4 stop·우선 stop 477건이 모두 같다)을
    네 자로 쟀다. 후보에 **(b′) 계획 시작 기준**을 더했다 — (b) 가 이 데이터에서 무엇을 하는지
    보고 나서다.

    - **(a) 의 흔들림이 재려는 차이보다 크다.** `large` A → C′ 의 총비용 차이는 −58,084원인데
      **(a) 하나가 +64,018원**을 움직인다. 그 계획은 거리 −51,772 · 시간 −53,430 · 지각 stop
      24 → 11 · **우선 stop 지각 3 → 0** 이다. 실제 항이 전부 좋아졌는데 이 자만 벌한다.
    - **(c) 는 절반의 처방이다.** A 와 C′ 은 라우트 수도 평균 길이도 같아 「쪼개기」 효과가 아예
      없는 비교인데, (c) 가 **+51,213**(a 의 80%)을 움직인다. 남은 민감도는 **순번 재배치**다.
    - **(b) 는 상수 보너스가 된다.** `large` 에서 우선 stop 의 **76%가 약속창이 열리기 전에
      도착**하므로(평균 89.5분 이르다) 477건 중 361건이 만점이고, 총합은 이론상 최대와 **0.3%**
      차이다. 상수 보너스는 이 구현이 `÷ position` 을 넣은 바로 그 이유를 되살린다.
    - **(b′) 만 변별력과 방향을 함께 갖는다.** 만점이 없고, 움직임은 (a) 의 **1/6** 이다.

    **결정: (b′) 채택** — `bonusKrw × priority × τ ÷ (τ + t)`, `t` 는 **계획 시작** 대비 계획
    도착 분, `τ = halfLifeMinutes` 기본 **12분**([ADR-040](adr/ADR-040-priority-boost-decays-in-time.md)).
    **근거는 변별력이 아니라 정책이다.** `priority` 는 파생값이고([ADR-028](adr/ADR-028-unassigned-policy.md))
    출처가 셋인데 — 약속 개정(+2) · 냉장(+1) · 재배송(+3, Phase 5) — 앞뒤의 「다시 늦지 않게」와
    냉장의 「차에 실린 시간을 줄여라」가 **둘 다 출발 대비 도착 시각에 단조**다. 차이는 「안전해진
    뒤에도 계속 상을 주는가」라는 모양뿐이고, (b) 의 포화(76%)가 보여 준 것은 이 데이터셋에서
    대부분이 **이미 안전하다**는 사실이다. 냉장의 요구는 안전해진 뒤에도 남으므로 세 출처를 하나로
    재는 변수는 출발 기준 하나다.

    **반론은 기각이 아니라 의존으로 적었다**: (b′) 는 약속과 무관하게 이른 것을 상 준다 — 창이
    14시에 열리는 고객을 6시에 배송하는 계획에 보너스를 준다. 그것이 옳은 이유는 §2.2 가 조기
    배송을 허용하고 지각만 벌하기 때문이고, **그 전제 위에서만 옳다.** 고객이 창을 고르는 티어가
    생기거나 조기 배송이 비용이 되면 이 자는 재검토 대상이다 — §6.3 에 의존 경고로 박았다.
    기준점을 **라우트 출발이 아니라 계획 시작**으로 둔 것도 같은 종류의 이유다: 라우트마다 0점이
    다르면 「쪼개면 앞자리가 늘어난다」가 시각으로 되살아난다.

    **판단 기준은 「어느 정의가 §6.3 문장을 가장 정직하게 재는가」 하나다. 어느 것이 `large` 를
    15% 넘기는가는 기준에서 뺀다** — 이 문장을 ADR 본문에 박는다. 빼지 않으면 이 작업은 「불리한
    자를 바꾼 것」이 되고, Phase 4 의 모든 수치가 «자를 바꿔서 좋아진 것» 이 된다.
    (참고로 **(a) 를 그대로 두어도 C′ 은 −15.07%** 로 이미 넘는다 — 이 결정은 DoD 를 사는
    결정이 아니다.)

    **자를 바꾸면 전 데이터셋 재기준이고 `baseline-nn` 절대값도 바뀐다** — 소프트 룰은 두 전략이
    똑같이 무는 항이므로 동결 계약(§6.9 게이트 규칙 1)의 범위 그대로다. 별도 ADR 로만 낸다.

    **재기준 완료** (`3313504`, [측정](benchmarks/phase4-priority-boost.md) §8).

    | 데이터셋 | `baseline-nn` 전 → **후** | `sweep-greedy-nn+ls` 전 → **후** | 기준 대비 |
    |---|---:|---:|---|
    | `small` | 1,531,480 → **1,517,523** | 1,136,026 → **1,120,725** | −25.82% → **−26.15%** |
    | `medium` | 4,636,549 → **4,588,065** | 3,893,515 → **3,848,378** | −16.03% → **−16.12%** |
    | `large` | 9,682,478 → **9,577,578** | 8,281,646 → **8,182,826** | −14.47% → **−14.56%** |
    | `peak` | 28,624,728 → **28,369,930** | 21,774,900 → **21,739,077** | −23.93% → **−23.37%** |

    - **자만 바뀌었다는 증명**: `baseline-nn` 은 소프트 룰이 계획을 만들지 않는 전략이라 네
      데이터셋에서 **거리·미배정·지각 stop 이 전부 동일**하고, 총비용 차이가 **소프트 항의 차이와
      정확히 같다**(−13,957 / −48,484 / −104,900 / −254,798).
    - **자와 무관한 항도 움직였다**: 기본 전략의 주행거리가 네 데이터셋에서 모두 줄었고 `large` 는
      **−58,310 m**다. 순번 자는 보너스를 거리로 사고 있었다. `peak` 의 남은 미배정 1건도 0 이 됐다.
    - **개선 단계 없이 재면 두 데이터셋이 나빠진다**(`sweep-greedy-nn`: `small` +26,430 ·
      `peak` +727,034, 미배정 13 → 30). 시퀀서가 다음 stop 을 「이동비 + 소프트 페널티」로 고르기
      때문이다(§6.5 4단계). **고치지 않는다** — 고른 기준은 「어느 자가 §6.3 문장을 정직하게
      재는가」이지 「어느 자가 어느 전략에 유리한가」가 아니다.

19. **`peak` 의 216 라우트 — 끝점 이웃을 넓히면 어디까지 내려가는가** (2026-09-17 열림,
    [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) 2·3번이 이 항목을
    전환 조건으로 삼는다).

    **순서는 리포트(5) 앞이다** — 비용을 바꾸는 마지막 항목이라 뒤로 미루면 발행 직후 재기준할
    수치로 리포트를 내는 것이 된다. 4-18 을 앞당긴 것과 같은 이유다.

    ADR-043 의 전환 조건은 **구조**다: 「`peak` 구성 라우트 수 ≤ 차량 수 × 1.2(= 106)」. 지금은
    **216개**이고, 그중 128개가 부착에서 밀려 **3,880 stop 이 재삽입으로 내려간다** — 30초 예산을
    3초 넘기는 원인의 97%가 그것이다([측정](benchmarks/phase4-savings-cw.md) §4.1).

    **원인은 K 다.** 병합 후보를 `Neighborhood.DEFAULT_K = 20` 안에서만 만들므로, 끝점의 이웃
    20개가 *이미 같은 라우트에 들어간* stop 들로 채워지면 그 라우트는 이을 후보가 없어진다.
    두 번째 패스가 한 건도 더 잇지 못하는 것(4-2 의 「재 보고 넣지 않은 것」)이 그 증거다 —
    **같은 K 안에서 이미 고정점**이다.

    **그림자 계측부터다**(§6.9 — 상한을 만들기 전에 잰다). `peak` · `large` 에서 재는 것 셋:

    - 부착에 밀린 라우트의 **크기 분포** (peak 128개 / large 35개 안팎)
    - 그 라우트들의 **끝점에 한해** 이웃을 넓혔을 때(K_end = 50 · 100 · **∞**) 이어지는 병합 수와
      거절 분해 — 특히 **집계 좌석 불변식을 통과하는가**
    - 라우트 수가 **얼마까지** 내려가는지

    전체 K 를 키우는 것이 아니라 **작은 라우트의 끝점만**이다. 근사를 덜 하는 방향이므로 결과는
    **개선 쪽으로만 틀린다** — 표 밖의 병합을 못 보던 것을 보게 될 뿐, 실행 불가능한 병합을 하지는
    않는다([ADR-042] 의 K 근사 논증과 같은 형태).

    **판정**: 상한이 `peak` 라우트 수를 **106 아래로** 내리면 만든다. 아니면 **수치와 함께
    닫는다** — 그때의 차선은 ADR-043 기각안의 「구조 조건 폴백」(라우트 수 > 차량 × 1.5 이면 그
    계획만 스윕으로)이고, 그것은 원인을 두고 증상을 우회하는 것이므로 차선이지 답이 아니다.

    **✅ 완료** (2026-09-17, [ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) ·
    [측정](benchmarks/phase4-endpoint-merges.md)).

    **그림자가 낸 상한이 조건을 넘었다 — 그리고 상한이 곧 구현이었다.**

    | `peak` | 라우트 | 2단계 병합 | 밀린 라우트 | 2단계 시간 |
    |---|---:|---:|---:|---:|
    | 없음 (K=20) | **216** | — | 128 (3,596 stop) | — |
    | K_end = 50 | 184 | 32 | 96 | 106 ms |
    | K_end = 100 | 157 | 59 | 69 | 206 ms |
    | **끝점 전부 (∞)** | **90** | **126** | **2** | **38 ms** |

    ∞ 가 **가장 싸다**. 끝점은 216개뿐이라 쌍이 46,090개인데, K_end 를 넓히는 쪽은 이웃 표를
    stop 8,411개 전부에 대해 다시 만들어야 한다(`O(n²)`). **근사가 정확한 계산보다 비싼
    구간**이고, 그래서 근사할 대상이 아니었다. 남아 있던 것도 분포가 말한다 — 216개 중 **67개가
    stop 하나짜리**였고(밀리던 128개의 절반), 2단계 뒤에는 0 이다.

    **조건은 충족됐다**: 라우트 **90** ≤ 106 · `peak` 이 **13,164 ms 에 수렴**(전: 30,007 ms
    잘림) · 네 전략을 한 JVM 에 올린 실행에서도 「어느 회차도 마감에 잘리지 않았다」 · 기본
    전략보다 **772,025원 싸다**.

    **그런데 같은 변경이 `small` 을 깼다**(같은 커밋 A/B): 1,073,363 → **1,262,833**(+17.65%),
    주행거리 **+17 km**, 미배정 0 → **3**(hazmat). `large` 도 2단계 전보다 **+105,502** —
    차를 5대 덜 쓰는 대신(고정비 −145,000) 남은 35대가 더 오래 한다(시간비 **+147,689** ·
    소프트 **+104,910**, [ADR-040](adr/ADR-040-priority-boost-decays-in-time.md) 의 시간 감쇠).

    **[ADR-044] 는 유지하고 기본 전략은 바꾸지 않는다.** 지난번 이유(재현되지 않는다)는
    해소됐고 이번 이유는 **`small` 에서 기본보다 13.4% 비싸다**는 것이다. 남은 조건은 「`small`
    에서 savings ≤ 기본」이고, 그것을 만드는 항목이 **4-20** 이다.

    > **상한이 조건을 넘긴 것과 총비용이 좋아지는 것은 다른 명제다.** 상한은 라우트 수를 쟀고
    > 라우트 수는 목적함수가 아니다 — §6.9 가 상한만으로 결정하지 말라고 한 이유가 이것이다.

20. **구성이 거리만 보는 것 — 2단계의 수락 기준이 목적함수가 아니다** (2026-09-17 열림, 4-19 가
    열었다. [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) 의 남은 전환
    조건이 이 항목에 걸려 있다).

    **⏸ Phase 4 백로그로 이월한다** (2026-09-18 결정). 그림자 계획(a·b·c)은 그대로 두고
    **Phase 7-4 의 peak-day 시뮬레이션이 이 항목을 다시 연다.** 이월의 근거는 측정이 아니라
    **종료 조건**이다 — 「비용을 바꾸는 항목은 리포트 앞」이라는 규칙에는 끝이 없다. 측정할수록
    다음 손잡이가 나오는 것은 방법론이 작동한다는 뜻이지 리포트를 미룰 이유가 아니고, 리포트는
    **커밋 SHA 에 귀속되는 스냅샷**이라 마지막 말이 아니다(§6.9).

    **(a) 의 방향이 맞다는 것은 지금도 적어 둘 수 있다.** `large` 의 분해가 그것을 보였다 —
    2단계가 거리를 **−36 km** 줄이는데 총비용은 **+105,502** 이고 움직인 항은 **시간비
    +147,689 · 소프트 +104,910** 이다([측정](benchmarks/phase4-endpoint-merges.md) §5).
    **라우트가 큰 2단계에서 거리는 더 이상 변동비의 대리 변수가 아니다.**

    savings 의 수락 기준은 `s(i,j) = d(0,i) + d(0,j) − d(i,j)` — **거리**다. 1단계에서는 그것이
    목적함수의 좋은 대리 변수다(라우트가 짧아 도착 시각이 이르고, 고정비는 라우트 수에 걸린다).
    **2단계에서는 아니다.** 4-19 의 손해가 난 자리가 그것을 말한다 — `large` 에서 거리는 −36 km
    인데 총비용이 +105,502 이고, 움직인 항은 **시간비(+147,689)와 소프트(+104,910)**다.

    후보(만들기 전에 상한부터, §6.9):
    - **(a) 비용 인식 수락** — 2단계에서 병합 전후의 *변동비*(거리·시간·소프트)를 실제로 재고,
      나빠지면 잇지 않는다. `RouteAccumulator` 가 이미 쌓아 보고 있으므로 비용은 그 자리에서
      나온다. 고정비는 **라우트 수가 차량 수 이하일 때만** 실제로 줄어든다는 점을 함께 봐야 한다.
    - **(b) 2단계를 라우트 수 조건으로 켠다** — 붙을 수 있을 만큼만 잇는다. 다만 `small` 은
      1단계에서 이미 17 > 5 라 이 조건으로는 꺼지지 않는다(4-19 측정). **먼저 재서 기각하거나
      채택한다.**
    - **(c) `small` 의 미배정 3건만 따로 본다** — 큰 라우트가 차를 채우고 나면 희소 조합 stop 이
      들어갈 자리가 없다. [ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) 의 좌석이
      *배정* 단계에서 하던 일을 구성이 덮은 형태다.

    **판정 기준은 다섯 데이터셋 전부다.** 4-19 가 하나(`peak`)를 고치고 하나(`small`)를 깬 것이
    이 기준을 세운 이유다.


### Phase 4 마감 대조표

기준일 **2026-09-18**. CLAUDE.md 「작업 방식」 — *기억이 아니라 표로 확인한다*. **빠진 항목은
표에 남긴다**(지우지 않는다: Phase 1 의 레이트 리밋이 그렇게 빠질 뻔했다).

> **커밋 열의 SHA 는 `main` 에서 그대로 유효하다** (2026-09-18 확인). 원래 이 자리에는 「브랜치
> `phase4/relay-advisory-lock` 의 것이므로 squash 머지 뒤에 다시 적는다」고 적혀 있었다(§6.9 —
> 브랜치 커밋을 적었다가 참조가 하루 만에 죽은 적이 있다). **그래서 이 PR 만 머지 커밋으로
> 넣었다**(`4ebfa3f`, PR #35): 44 커밋이 하나로 접히면 이 표의 30개 SHA 가 전부 `#35 <하나>` 가
> 되어 **항목별 귀속이 사라진다** — 표를 두는 이유 자체가 없어진다. 저장소의 다른 PR 은
> squash 그대로다(Phase 3 대조표의 `#30 2960d9b` 형태).

| # | 작업 (계획 문장) | 상태 | 커밋 / 근거 |
|---|---|---|---|
| 0 | 릴레이 리더 락을 PostgreSQL advisory lock 으로 | ✅ | `bd536ee`(ADR-027 후속 정정) · `c3ca8ca`(구현) · `6333ce9`(데모 전제 어설션) · `7dad296`(격리 축 둘) · `cbb48f6`(순서 결정) |
| 1 | `LocalSearchImprover` — §6.5 5단계 | ✅ | `70f0f32`([ADR-032](adr/ADR-032-local-search-budget-and-approximations.md)) · `49f0903`([ADR-031](adr/ADR-031-least-capable-first-tie-break.md) 동률) · [측정](benchmarks/phase4-local-search.md) |
| 2 | `savings-cw+ls` 전략 | ✅ | `5cb820a`([ADR-042](adr/ADR-042-savings-merges-are-class-aware.md)) · `ea831cd`([측정](benchmarks/phase4-savings-cw.md)) · `b2aa3ee`([ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md)) |
| 3 | 병렬화 (ForkJoin + 가상 스레드) | ⏸ **이월** → 7-0 A12 | `9c3bcd0`([ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md)) · `8846272`([측정](benchmarks/phase4-peak-gate.md)). **병렬 단위가 클러스터가 아니었다** — 상한을 먼저 쟀고 만들 값이 없었다. 재검토 조건은 ADR-035 |
| 4 | FAST 열화 모드 | ✅ | `a7a0717`([ADR-034](adr/ADR-034-degrade-mode.md)) · `6af4eee`(후속 정정 — 사다리 두 단) · [측정](benchmarks/phase4-fast-mode.md) |
| **5** | 벤치마크 리포트 · README 표 링크 | ✅ | **이 커밋** — [`phase4-strategies.md`](benchmarks/phase4-strategies.md). 다섯 데이터셋 × 네 전략 · 사다리 두 단 · 고정비 하한 · 재기준 이력 · 그림자 계측 원장 |
| 6 | (선택) `timefold` 전략 실험 → ADR-004 에 수치 반영 | ⛔ **범위 제외(결정으로 닫았다)** | `f65c8b2` 시점에는 「⬜ 미구현」이었고, **표가 그것을 들고 있었기 때문에 결정이 됐다** — [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)(2026-09-18): 비교 대상을 다른 솔버가 아니라 **불가능의 경계**로 둔다. 근거 셋은 고정비 하한 열(상시, [ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md)) · 그림자 원장 여덟 줄 · 구성 계열이 다른 두 전략 비교. **다시 여는 조건 셋**과 한정 실행(Phase 7-6 `medium` 한 개)을 ADR 이 적는다 |
| 7 | ADR-004, 008 확정 | ◐ **부분** — 004 ✅ / 008 ⏸ Phase 7-6 → 7-0 A12 | **004 확정**: [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)(2026-09-18) — Phase 4 의 마지막 커밋. **008 이월**(가상 스레드 + ForkJoin 분리): **3번이 이월되면서 근거가 바뀌었다** — [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) 가 「병렬 단위는 클러스터가 아니다」를 이미 확정했으므로, 확정은 Phase 7-6 의 「ADR 전체 확정(001–012)」에서 그 결과를 안고 쓴다 |
| 8 | `small` 레짐 격차 | ✅ **닫혔다** | `70f0f32`(개선 단계) · `15fa48f`([ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md) — **진짜 원인은 좌석이었다**) · `b36c96e`(축 재측정) · `a1cc31e`+`4721c6c`([ADR-041](adr/ADR-041-cluster-target-counts-stop-slots.md)). 지금 `small` 은 `sweep-greedy-nn` −15.80% · 기본 전략 −26.60%. **게이트는 `medium` 으로 둔다**(근거가 결과가 아니라 자유도다) |
| 9 | 테스트 격리 — 픽스처가 정하지 않은 축 | ✅ **닫힘 — Phase 5-0**(이 표를 쓴 시점에는 ◐ 부분 — Phase 4 안에서) | `7dad296`(플래너 통계·컷오프 상한) · `49f0903`(배정 동률) · `7f2bc9d`(검사 대상 집합). **남은 둘은 Phase 5-0 (2026-09-18)**: ① **시드 행** — 고치던 것이 `camp_id IS NULL` 인 **전역** 시드 룰이었다(전제 어설션이 `but was: null` 로 잡았다). 캠프 범위 픽스처 행으로 바꿨다 ② **릴레이 리더의 fulfillment 쪽** — 둘째 컨텍스트(`GeoFallbackIT`)가 릴레이를 켠 채 같은 advisory lock 을 두고 겨뤘다. 발행을 보지 않는 IT 가 끄고, 보는 둘은 `lead()` 를 첫 어설션으로 묻는다. **커밋은 Phase 5-0** (브랜치 SHA 는 squash 로 죽으므로 항목으로 가리킨다, §6.9) |
| 10·11 | 미배정 정책 + 우선도 파생 + 재삽입 | ✅ | `d7b4c79`([ADR-028](adr/ADR-028-unassigned-policy.md)) · [측정](benchmarks/phase4-unassigned-policy.md). 셋을 **하나의 결정**으로 묶었다. 「재배송 +3」은 사실을 만드는 **Phase 5** 로 미뤘다 |
| 12 | `make demo`·CI 스모크가 하루 8시간 실패한다 | ✅ | `f71e9a3`([ADR-030](adr/ADR-030-night-shift-seed.md) — 부록 A 에 야간 근무조) · `c086c0d`(야간조 냉장 배분 정정) |
| 13 | 계획 영속화 20초 | ✅ | `6f72c1a`([ADR-029](adr/ADR-029-optimizer-io-is-bulk-not-orm.md) — 20.4초 → 800 ms) · `76082e1`([측정](benchmarks/phase4-plan-roundtrip-breakdown.md)) |
| 14 | 겹친 제약은 한 대에 몰리지 않는다 | ✅ | `c485831`([ADR-033](adr/ADR-033-constraint-classes.md)) · [측정](benchmarks/phase4-constraint-classes.md) |
| 15 | 안 훑기 — 라우트 쌍 dirty 플래그 | ⛔ **종료(만들지 않는다)** | `8846272`([측정](benchmarks/phase4-peak-gate.md) §3 — 상한 4.0~17.2%, 잘못된 건너뛰기 0) · `154e361`(§6.9 규칙화). **«측정했고, 만들 이유가 측정되지 않았다»** |
| 16 | `peak` 이 드러낸 셋 (16-a·b·c) | ✅ | `de04f15`(16-a 데이터셋 둘로 + 실현 가능성 stop 축) · `3a303ee`(16-b [ADR-036](adr/ADR-036-deadline-belongs-to-the-plan.md) 계획 전체의 마감) · `545c669`(16-c [ADR-037](adr/ADR-037-reinsertion-prunes-what-cannot-fit.md) 재삽입 가지치기) |
| **17** | 희소 능력 좌석 — 좌석은 능력이 아니라 **제약 조합**에 예약한다 | ✅ **구현** | `a533c95`(그림자 계측 ⓐⓑⓒ) · `1a10f0b`+`edc6ff4`+`aac442b`([ADR-038](adr/ADR-038-fixed-cost-floor-is-not-a-total-cost-floor.md) · `routeStopCap` · 고정비 하한 열) · `f19df5f`(귀속 정정) · `15fa48f`([ADR-039](adr/ADR-039-reserve-seats-by-constraint-class.md)) · `c99cf3b`([재기준](benchmarks/phase4-scarce-seats.md) — **상한이 아래쪽으로 틀렸다**) |
| **18** | `priority-boost` 의 `÷ position` — 룰 설계 검토 | ✅ **구현(자를 바꿨다)** | `426eca9`(순서를 앞당긴 근거) · `de5ae73`([그림자 계측](benchmarks/phase4-priority-boost.md) — 재는 자가 재려는 차이보다 크게 흔들렸다) · `3313504`([ADR-040](adr/ADR-040-priority-boost-decays-in-time.md)) · `2e41380`(전 데이터셋 재기준, `baseline-nn` 절대값 포함) |
| **19** | `peak` 의 216 라우트 — 끝점 이웃을 넓히면 | ✅ **구현** | `c565188`([ADR-044](adr/ADR-044-endpoints-are-few-enough-to-see-all.md) — 끝점 전부, 라우트 216 → 90) · `1db33b9`([판정](benchmarks/phase4-endpoint-merges.md) — 조건 충족, **그리고 `small` 이 깨졌다**) |
| **20** | 구성이 거리만 보는 것 — 2단계의 수락 기준 | ⏸ **이월(Phase 4 백로그)** → 7-0 A4 | 커밋 없음. **Phase 7-4 의 peak-day 시뮬레이션이 다시 연다.** 그림자 계획(a·b·c)은 20번 항목에 그대로 있고, **(a) 비용 인식 수락의 방향이 맞다**는 것은 `large` 의 분해가 이미 보였다(거리 −36 km · 시간비 +147,689) |

**대조표가 잡은 것 셋**

1. **6번과 7번은 표가 없었으면 조용히 넘어갔다.** 둘 다 「하지 않았다」가 결론인데, 그것이
   *검토한 결과* 라는 사실은 표에만 남는다. 특히 7번은 **3번이 이월되면서 근거가 바뀐** 항목이다.
   **그리고 6번은 표에만 남지 않았다** — 마감 검토가 「⬜ 미구현」을 집어 [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md)
   로 닫았다(2026-09-18). 조건 없는 이월은 결정이 아니라 기억에 맡기는 일이라, 기각에
   **다시 여는 조건 셋**을 붙였다. 7번의 004 도 같은 커밋에서 확정된다.
2. **9번은 ✅ 가 아니라 ◐ 다.** 여섯 축 중 넷을 고쳤고 둘이 남았다. 남은 둘은 「지금 깨지지
   않는 이유가 테스트에 적혀 있지 않다」는 같은 부류이고, **Phase 5 의 tracking IT 가 같은
   DB·같은 릴레이를 쓰기 시작하면 ②가 먼저 깨진다.**
3. **20번은 Phase 4 의 마지막 측정이 연 항목이고, 닫히지 않은 채로 넘어간다.** 열려 있는 것을
   열린 채로 적는 것이 이 표의 목적이다 — [ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md)
   의 남은 전환 조건(「`small` 에서 savings ≤ 기본」)이 거기에 걸려 있다.

**DoD 대조는 [`phase4-strategies.md`](benchmarks/phase4-strategies.md) §8 에 있다** — 네 줄 중
첫 줄(`large` ≥ 15%)이 **기본 전략에서는 미달(−14.93%, 6,353원 부족)** 이고 비교 전략이 −18.20%
로 넘는다. **두 수를 함께 적는 것이 그 줄의 규칙이다.**

---

## Phase 5 — tracking-service + 기사 시뮬레이션 + 재계획

**작업 순서** (2026-09-18 승인) — **0 → 1a → 1b → 2 → 5 → 3**. 4(테스트)는 각 항목 안에서.
근거 넷: ① **0번은 다음 Phase 의 첫 IT 에서 먼저 깨진다**(Phase 4 마감 대조표 9번) ② ETA·at-risk 는
3번의 **트리거**라 3번 앞에 있어야 하고, 골격이 아니라 tracking 의 핵심 동작이다 — 그래서 1번을
**1a·1b 로 가른다** ③ 2번 시뮬레이터가 **1b 의 입력**을 만든다 ④ 5번이 3번의 전제(「미완료 stop 만」)를
만든다.

**작업**
0. **테스트 격리 축 둘 — Phase 4-9 이월** (✅ 2026-09-18).
   ① **시드 행**: `DispatchAdminIT` 가 고치던 것은 `camp_id IS NULL` 인 **전역** 시드 룰이었다.
   되돌리는 대신 **캠프 범위 픽스처 행**을 만들어 고치고 지운다 — 되돌리기는 순차 실행에 기대는
   장치이고, 지우기는 「무엇을 덮는가」를 묻지 않는다.
   ② **릴레이 리더(fulfillment)**: 자기 `@DynamicPropertySource` 를 가진 `GeoFallbackIT` 가 둘째
   컨텍스트라 릴레이가 둘이었고 advisory lock 은 하나다([ADR-027](adr/ADR-027-outbox-relay-leader-lock.md)
   후속 정정). 발행을 보지 않는 IT 가 자기 자리에서 끄고, 보는 IT 둘(`FulfillmentPublishIT`·
   `WaveLifecycleIT`)은 **`lead()` 가 `LEADER` 인가**를 첫 어설션으로 묻는다 — 예전 전제
   「릴레이 빈이 있다」는 리더십을 한 마디도 말하지 않았다.
1a. **tracking 골격**: `Shipment` 상태 머신, `route.assigned` 소비(revision 비교), 스캔 이벤트 API,
   Flyway(§5.4, 일 파티션 생성 스케줄러).

   **상태 머신에 `CANCELLED` 가 있다** — `SCHEDULED`·`OUT_FOR_DELIVERY` → `CANCELLED`
   (`route.assigned` 의 `cancelledOrderIds`·`status:CANCELLED` 를 반영하므로 받을 상태가 있어야
   한다). 축 규칙의 **tracking 자리**도 여기서 정한다: 미래 상태 건너뜀은 수용, 역행은 무시,
   그리고 **`CANCELLED` 뒤에 오는 스캔은 무시하되 센다**(`dawnline_scan_after_cancel_total`) —
   기사가 취소를 받지 못하고 배송한 경우이고, dispatch 의 `dawnline_cancel_too_late_total`(§6.10
   넷째 분기)과 **한 쌍**이다.

   **revision 은 종결 상태를 되돌리지 않는다.** §6.8 의 부분 재계획은 완료 stop 을 고정하지만,
   tracking 은 그것을 페이로드가 아니라 **자기 규칙으로** 지킨다 — `COMPLETED`/`FAILED` 인
   shipment 는 새 revision 이 와도 그대로 두고, 나머지만 `plannedArrival`·`eta` 를 갱신한다.
   5번 전에는 dispatch 가 진행 상황을 모르므로 **이 규칙이 tracking 쪽의 유일한 방어선**이다.

   **조건은 「완료했는가」가 아니라 「종결인가」다** (2026-09-19 정정 — 코드가 옳고 이 문장이
   좁았다). `CANCELLED` 도 같은 줄에 걸린다. 앞의 둘과 이유는 다르다: 되돌릴 것이 있어서가
   아니라 **갱신할 것이 없어서**다 — 취소된 배송의 계획 도착 시각을 옮기는 일은 아무 물음에도
   답하지 않는다. 그래서 구현은 `ShipmentStatus.isTerminal()` 한 번이고, 상태가 늘어도
   그 줄은 그대로다(§5.4).

   **계약 변경 하나 — `route.assigned.v1` 의 stop 에 `promisedWindow`(required)**. tracking 이
   정시 여부와 at-risk(`eta > promised_end − 15분`, §5.4)를 판정하려면 stop 마다 약속창이
   필요한데 `plannedStop` 에 없다. dispatch 는 갖고 있다 — `StopMerger` 의 병합 키가
   「같은 geohash7 + **같은 약속창** + 같은 제약」이라 stop 당 창이 하나로 정해진다(§6.5 1단계).
   `required` 인 근거는 `contracts/events/README.md` §5 의 예외 조건 셋이고, 그 표에 한 줄
   남긴다. **운영 메모**: 개발 볼륨의 Kafka 에 남은 이전 `route.assigned` 이벤트에는 이 필드가
   없으므로, tracking 의 컨슈머 그룹은 `latest` 에서 시작하거나 그 토픽을 재생성한다 — 적지 않으면
   첫 기동에서 DLQ 가 찬다.

   **스키마 둘이 붙었다** (2026-09-19). ① `route_revisions` — §8.5 의 「routeId + revision」
   비교는 라우트당 마지막 개정을 알아야 성립하고, `shipments` 에서 MAX 로 유도하면 §6.8 의
   `relocate` 가 라우트를 비웠을 때 비교할 값이 사라진다(§5.4 의 버린 대안 둘).
   ② `shipment_events` 의 일 파티션은 **DEFAULT 파티션 없이** 마이그레이션의 함수 둘이
   만들고 스케줄러가 부른다 — 범위 밖 행이 조용히 쌓이면 그 날짜의 파티션 생성이 며칠
   뒤에 실패한다. 생성이 멈춘 것은 `dawnline_shipment_partitions_ahead`(§9.1)가 말하고 알림은
   2 에서 걸린다(§9.4).

   **원 약속 대비 정시율은 tracking 이 내지 않는다.** `order.placed`(원본)와 `delivery.status`
   (완료 시각)를 잇는 곳은 **ops-api 의 읽기 모델**이다(§5.5 — Phase 2 가 예약해 둔 「두 정시율」의
   자리를 여기서 확정한다). tracking 은 자기가 가진 약속(개정본)만 본다.
1b. **ETA·at-risk**: ETA 재계산·전파, at-risk 판정, 쿨다운(라우트당 5분, Redis `SET NX`),
   `delivery.status`/`delivery.at-risk` 발행.

   **전파는 애그리거트 밖이다**(`EtaPropagator`) — 편차는 라우트의 성질이고 「어디서 움직이는가」의
   답이 하나여야 한다. 애그리거트가 받는 것은 결과값 하나(`Shipment.projectEta`)이고, 종결 상태를
   옮기지 않는 판단만 그쪽의 것이다.

   **첫 편차의 출처는 출발이다** (2026-09-19 추가). 늦게 출발하는 것이 가장 흔한 지연 원인이고
   그것은 첫 `ARRIVED` 스캔 <em>전에</em> 이미 알 수 있다. 그래서 `DEPARTED_CAMP` 에서
   `d = 실제 출발 − 계획 출발` 을 전 stop 에 전파하고 그 자리에서 at-risk 를 판정한다. 계획 출발
   시각은 `route.assigned.v1` 의 `summary.plannedDeparture` 로 온다(**additive required**, 조건
   셋은 `promisedWindow` 때와 같다 — `contracts/events/README.md` §5 예외 표). 값은 dispatch 가
   이미 갖고 있었다: `RouteState` 의 출발 앵커이고 `PlannedRoute` 로 굳히면서 버려지고 있었다.

   **`DEPARTED_CAMP` 는 브로커로 나가지 않는다.** 라우트의 사건을 stop 수만큼 반복해 말하는
   꼴이고, order-service 의 상태 머신은 `DISPATCHED` 로 그 구간을 이미 덮는다. 운영자가 출발
   사실을 화면에서 원하면 라우트 단위 이벤트 하나를 **Phase 6 에서** 정한다(아래 6-0 옆의 메모).

   **at-risk 는 사건이지 상태가 아니다**([ADR-046](adr/ADR-046-at-risk-is-an-event.md)). 위험이
   사라지는 경우는 알리지 않는다 — 재계획을 취소할 방법이 없다. 그리고 **이 쿨다운이 지키는 것은
   알림 수이지 정확성이 아니다**: 재계획 중복을 막는 쿨다운은 dispatch 의 DB 에 있고, 그것은
   아래 3번의 몫이다.
2. `sim-runner` 기사 시뮬레이터: `route.assigned` 구독 → stop 순회(이동 시간 = 계획 시간 × (1 + 지연 확률·크기)), 실패 확률, 위치 보고.
   **seed 결정론**: 지연·실패 주입도 전부 seed 에서 뽑는다(불변규칙 12). 그리고 여기서 처음 흐르는
   `delivery.status` 에는 Phase 1 8단계 규칙대로 **브로커 도착 IT** 가 붙는다(`OrderPublishIT`·
   `FulfillmentPublishIT` 와 같은 형태).

   **정정(2026-09-19, 5-2 착수 시점).** 그 「브로커 도착 IT」는 <em>5-1b</em> 가 이미 채웠다 —
   `TrackingPublishIT` 이 스캔 → outbox → 브로커까지를 보고 팬아웃 수(`2 × stop 수`,
   `DEPARTED_CAMP` 는 0)까지 못박는다. 이 항목이 놓여 있을 때는 tracking 이 없었고 `delivery.status`
   가 5-2 에서 처음 흐를 것으로 보았지만, 실제 순서는 `0 → 1a → 1b → 2` 라 1b 에서 먼저 흘렀다.
   **대조표에서 이 줄은 5-1b 에 귀속한다.** 5-2 가 대신 보는 것은 시뮬레이터 자신의 계약이다:
   seed 동일 → 스캔 열 동일, 주입한 지연 = 편차, 개정 재진입, 404 재시도 상한, 그리고
   `SimDriverIT`(브로커 → 스캔 API 전 구간).

   **시뮬레이션 시각과 벽시계를 가른다.** 스캔의 `occurredAt` 은 `plannedDeparture`·`plannedArrival`
   에서 파생하고 벽시계를 읽지 않는다. 그래야 <em>주입한 지연이 곧 tracking 이 계산하는 편차</em>가
   되어 `late-injection` 이 값을 어설션할 수 있다. 배속(`speed`)은 대기에만 닿는다.
   그 결과로 기록해 둘 것 하나: at-risk 쿨다운 TTL 은 **벽시계 5분**이라 압축된 시간에서는
   라우트당 at-risk 가 한 번만 보인다. **시뮬레이터의 제약이지 tracking 의 규칙이 아니다** —
   `late-injection` 의 어설션은 「at-risk 가 났다」까지이고, 「몇 번 났다」는 배속 1에서만 의미가 있다.
3. dispatch 재계획(§6.8): `delivery.at-risk` 리스너, 미완료 stop 부분 재계획, `revision` 증가 발행, 쿨다운.

   **쿨다운은 첫 커밋에 함께 넣는다** — `routes.last_replanned_at` 을 재계획 트랜잭션 안에서
   비교·갱신한다([ADR-046](adr/ADR-046-at-risk-is-an-event.md) 결정 3). tracking 의 Redis
   쿨다운은 **알림 수**를 지키지 정확성을 지키지 않는다: Redis 가 죽으면 중복 at-risk 가 나가고
   (§7.2 가 허용으로 정한 폴백), 두 at-risk 는 `eventId` 가 달라 `processed_events` 가 막지
   못한다. **「쿨다운은 이미 있으니 됐다」가 이 자리의 함정이다** — 그 말이 나오면 재계획 중복은
   아무도 막지 않는다.
4. 테스트: 역행 스캔 거부, at-risk 1회 발행(쿨다운), 재계획 후 tracking이 새 revision만 반영.
5. **dispatch 의 `delivery.status` 소비 — `route_stops.status` 전이** (2026-09-05 결정,
   §4.1 소비자 목록 변경 완료). dispatch 리스너(`IdempotentConsumer`) → `route_stops.status` 를
   `ARRIVED`/`COMPLETED`/`FAILED` 로 옮기고, §7.2 의 `route:{id}:progress`(nextSeq/completed/failed)를
   채운다. **ADR 은 여기서 쓴다** — 결정은 §4.1 에서 이미 했고, 남은 것은 전이 규칙(역행 스캔,
   순서 뒤바뀜, revision 이 지난 라우트의 스캔)이라 코드와 함께 나와야 근거가 산다.

   이것이 푸는 것이 셋이다: §6.8 부분 재계획의 "미완료 stop 만", `route:{id}:progress` 의 dispatch
   쪽 입력, 그리고 §6.10 넷째 분기와 `dawnline_cancel_too_late_total`
   ([ADR-026 후속 정정](adr/ADR-026-dispatch-cancellation-window.md)). **그때까지 그 카운터가 0 인
   것은 버그가 아니라 미구현이다** — 그 구분이 없으면 다음 사람은 0 을 보고 "경합 창이 좁다" 고 읽는다.
   계약 변경은 없다(`delivery.status.v1` 이 `routeId`·`stopSeq`·`orderIds` 를 이미 required 로 든다).

**축소안**: 재계획(3번)을 "운영자 수동 재배정 API"로 대체.

**DoD**
- `late-injection` 시나리오에서 at-risk → 재계획 → revision 반영이 로그·DB로 확인되고, 정시율이 `rm_kpi`/메트릭에 집계됨.
- **5번에서 소비자 처리량을 다시 잰다.** Phase 4-0 이 조건을 걸어 둔 항목이다 —
  [측정](benchmarks/phase4-plan-roundtrip-breakdown.md) §3 이 **1,638 건/초**(§8.2 피크 600 rps 의
  **2.7배**)를 내면서 「소비 경로가 무거워지면(Phase 5-5 의 `delivery.status` 소비가 같은 서비스에
  붙는다) 다시 재야 한다」고 적었다. 5번은 dispatch 에 리스너를 하나 더 붙이고 그 이벤트는
  **stop 단위**라 주문 수의 몇 배가 된다. 그 여유가 어디로 가는지가 §8.2 판정의 입력이다.
  **그 배수는 5-1b 의 `TrackingPublishIT` 이 어설션으로 못 박아 둔다** — 스캔 하나가
  `delivery.status` 한 건이고 `DEPARTED_CAMP` 는 **0 건**이므로 라우트당 이벤트 수는
  `2 × stop 수`(도착 + 완료)다. 문서에 적으면 코드가 바뀔 때 함께 바뀌지 않으므로 수치는
  그 IT 에 있다.

---

### Phase 5 마감 대조표

기준일 **2026-09-23**. CLAUDE.md 「작업 방식」 — *기억이 아니라 표로 확인한다*. **빠진 항목은
표에 남긴다**(지우지 않는다).

> **커밋 열은 머지 커밋 SHA 다.** Phase 5 의 PR 은 전부 머지 커밋으로 들어갔으므로 `main` 에서
> 그대로 유효하다(Phase 4 대조표의 같은 문단). 아직 머지되지 않은 것은 **PR 번호로** 가리킨다 —
> 브랜치 SHA 는 squash 에서 죽는다(§6.9).

| # | 작업 (계획 문장) | 상태 | 커밋 / 근거 |
|---|---|---|---|
| 0 | 테스트 격리 축 둘 — 시드 행 · 릴레이 리더(fulfillment) | ✅ | `4ebfa3f`(#35). Phase 4 대조표 9번의 이월이고 **여기서 닫혔다** — 캠프 범위 픽스처 행(되돌리지 말고 만들고 지운다) · 발행을 보지 않는 IT 가 자기 자리에서 릴레이를 끈다 |
| 1a | tracking 골격 — `Shipment` 상태 머신, `route.assigned` 소비(revision 비교), 스캔 API, Flyway | ✅ | `9bfa50f`(#37) · `e22704e`(#38 계약 가드). [ADR-045](adr/ADR-045-revision-comparison-is-per-route.md)(`route_revisions`) · 일 파티션 함수 둘 + 스케줄러 · `dawnline_shipment_partitions_ahead`. 계약 변경 둘: `promisedWindow`·`summary.plannedDeparture`(둘 다 additive required, `contracts/events/README.md` §5 예외 표) |
| 1b | ETA·at-risk — 재계산·전파, 판정, 쿨다운(Redis), `delivery.status`/`delivery.at-risk` 발행 | ✅ | `8c40f40`(#39). [ADR-046](adr/ADR-046-at-risk-is-an-event.md)(사건이지 상태가 아니다) · `EtaPropagator`(전파는 애그리거트 밖) · 첫 편차의 출처는 **출발**(`DEPARTED_CAMP`) · `TrackingPublishIT` 이 팬아웃 `2 × stop 수` 를 못박는다 |
| 2 | `sim-runner` 기사 시뮬레이터 — 구독·순회·지연·실패·위치 보고 | ✅ | `ec9d4cc`(#40) · `b5e9075`(#42 스캔에 송장을 싣는다). seed 결정론 · 시뮬레이션 시각과 벽시계를 가른다 · `SimDriverIT`(브로커 → 스캔 API 전 구간) |
| **3** | **dispatch 재계획(§6.8) — `delivery.at-risk` 리스너, 미완료 stop 부분 재계획, `revision` 증가 발행, 쿨다운** | ✅ | **PR #44** — [ADR-048](adr/ADR-048-replan-reads-its-own-db.md). 쿨다운은 **첫 커밋**에(`routes.last_replanned_at`, V10) · 편차는 `route_stops.actual_at` 에서(페이로드는 대조값) · `relocate` 세 조건 · `dawnline_replan_total{outcome}` 다섯 갈래 · `applied` 는 `plan_explanations`(`AT_RISK_RELOCATE`)에 설명을 남긴다 |
| 4 | 테스트 — 역행 스캔 거부, at-risk 1회 발행(쿨다운), 재계획 후 tracking 이 새 revision 만 반영 | ◐ **부분** → 7-0 A10 | 앞의 둘 ✅ (`ShipmentTest`·`AtRiskIT` — 1a·1b 안에서). 셋째는 **서비스 하나 안에서만** 닫혔다: dispatch 쪽은 PR #44 의 `ReplanIT`, tracking 쪽은 1a 의 revision 비교(`ADR-045`)다. **두 서비스를 잇는 한 시나리오는 없다** — 아래 DoD 첫 줄과 같은 빈칸이고, 그 자리는 §8.2 의 compose 시나리오다 |
| 5 | dispatch 의 `delivery.status` 소비 — `route_stops.status` 전이 | ✅ | `17e31db`(#41) · `b5e9075`(#42 사실은 주문에 귀속된다). [ADR-047](adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md) · `route_stop_orders (order_id)` 인덱스(V9, [측정](benchmarks/phase5-route-stop-orders-order-lookup.md)) · §6.10 넷째 분기가 처음으로 발화 가능해졌다 |
| — | 재배송(우선도 +3 의 사실 출처) — Phase 4 대조표 10·11 이 이 Phase 로 미뤘다 | ⬜ **미구현 → 범위 밖** | **(2026-09-25 추가, Phase 7-0 대조표가 잡았다)** 이 표에 행이 없었다 — 미룬 쪽의 표에만 있고 받은 쪽의 표에 없으면 빠진 것이 보이지 않는다. 재배송은 order·dispatch·tracking 을 가로지르는 새 흐름이라 범위 밖으로 닫았다(DESIGN §6.3 우선도 표 · ADR-028 재검토 지점) |

**DoD 대조**

| DoD 문장 | 상태 | 근거 |
|---|---|---|
| `late-injection` 시나리오에서 at-risk → 재계획 → revision 반영이 **로그·DB 로 확인** | ◐ **부분** → 7-0 A10 | dispatch 안에서는 `ReplanIT` 이 실물 브로커·실물 PostgreSQL 로 못박는다(at-risk → 쿨다운 → 옮김 → 두 라우트 revision 2 → `plan_explanations`). **compose 전 구간의 한 번은 없다** — `late-injection` 은 기사 시뮬레이터까지만 돌고 거기서 재계획을 보지 않는다. 그 자리는 §8.2 의 시나리오 확장이고 **Phase 7-4 의 peak-day 시뮬레이션**에서 닫는다 |
| 정시율이 `rm_kpi`/메트릭에 집계됨 | ✅ **닫힘 — Phase 6** (`7283caa`, #48 · 이 표를 쓴 시점에는 ⛔ 미구현) | 두 기준 정시율(`basis=promised/revised`)은 **ops-api 가 낸다**(§9.1 의 문단 · §8.1). tracking 은 개정본 약속 하나만 알아서 그 라벨을 만들 수 없다. 읽기 모델이 없는 지금은 구현이 아니라 **미구현**이다. **(2026-09-24, Phase 6 에서 구현)** — `kpi_delivery_hourly` 뷰와 `dawnline_delivery_on_time_ratio{camp,basis}` 게이지(§5.5 「KPI — 두 축, 뷰」) |
| 5번에서 소비자 처리량을 다시 잰다 (Phase 4-0 의 조건) | ✅ | [측정](benchmarks/phase5-delivery-status-throughput.md). 팬아웃 배수는 문서가 아니라 `TrackingPublishIT` 의 어설션이 든다 |

**대조표가 잡은 것 셋**

1. **4번은 ✅ 가 아니라 ◐ 다.** 「재계획 후 tracking 이 새 revision 만 반영」은 *두 서비스*의
   문장인데 검사는 서비스마다 따로 있다. 각자는 옳고, 그 둘이 한 시각에 맞물리는지는 아무도
   보지 않는다 — DoD 첫 줄과 **같은 빈칸**이고, 표가 그것을 두 번 말하는 것이 지금은 옳다.
2. **`route:{id}:progress` 의 「첫 소비자는 5-3」이 뒤집혔다.** 5-5 가 그렇게 적어 두었지만
   재계획이 필요한 것은 캐시의 세 칸이 아니라 `actual_at`·`planned_arrival` 이었다. 같은 행을
   어차피 읽으므로 캐시는 조회를 아끼지 않고 **같은 사실의 두 번째 출처**만 만든다.
   §6.8 과 [ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 에 정정으로 적었고, 그 캐시의 첫
   소비자는 ops 의 읽기 모델(§5.5, Phase 6)이 된다.
   **그 예상도 빗나갔다 (2026-09-23, Phase 6-0c — 키를 지웠다).** ops 는 이벤트로 프로젝션하고
   `GET /routes/{routeId}` 가 stop 마다 살아 있는 상태를 이미 돌려준다. 예정한 소비자가 **두
   번 연속** 오지 않은 것이고, 그것이 「쓰는 쪽을 먼저 둔다」가 이 키에서 틀렸다는 근거다 —
   부재는 첫 소비자가 채운다(§11).
3. **5-3 이 §5.3 의 결함을 하나 드러냈다.** `moveOrder` 가 목적지에 *새* stop 을 만들 때
   약속창을 비운 채 INSERT 하고 있었다(V6 이 그 칸을 더한 이유가 개정 발행이었는데도). 재배정의
   발행은 도메인 객체에서 페이로드를 만들어 그것을 덮고 있었고, **DB 를 읽는 발행 경로가 생겨서야
   보였다.** 검사는 발견한 자리(`DispatchAdminIT`)에 두었다 — 열거하지 않고 「창이 빈 행이 0」으로.

---

## Phase 6 — 백오피스 (ops-api + ops-web)

> **선결 — `rm_orders` 는 약속을 두 개 든다.** §8.1 의 정시율은 *원 약속* 기준인데
> order-service 의 `promised_start/end` 는 개정 경로에서 덮인다(ADR-020 결정 3 — 덮는 것이 맞다).
> 원 약속을 아는 곳은 `order.placed` 이벤트뿐이고, 두 기준을 모두 낼 수 있는 곳은 이 읽기 모델이다.
> §5.5 DDL 의 `promised_end` 한 칸으로는 `dawnline_delivery_on_time_ratio{basis}` 를 낼 수 없고,
> 그러면 **개정으로 정시율을 세탁할 수 있게 된다** — 두 값으로 내기로 한 이유가 바로 그것이었다.
> Phase 2-7 에서 order-service 쪽을 구현하며 드러났다.

**작업**
0-a. **(선결) `ProblemDetailsAdvice` 셋을 `libs/web` 으로 뽑는다**
   ([ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md)). order·tracking·dispatch
   에 거의 글자 그대로 있던 사본 셋이고 갈라지는 칸은 `RETRY_AFTER_SECONDS` 하나였다. **넷째가
   이 Phase 에 온다**(ops-api) — 그것이 이 뽑기의 실제 수요다.
   *6-0 앞에 두는 이유*: 생성물이 말하는 것의 절반이 오류 본문이라, 뽑기가 advice 를 바꾸면
   `dispatch-service.yaml` 을 두 번 만들게 된다. 그리고 계약 IT 의 어설션 한 벌(`OpenApiResponses`)이
   세 서비스에서 같으려면 advice 가 먼저 한 벌이어야 한다.
   자리가 `libs/common` 의 피처 변형이 아니라 **새 모듈**인 이유는 이 저장소의 가드 둘(`check` 의
   컴파일 의존 · JaCoCo `classDirectories`)이 피처 변형을 모르기 때문이다 — 모듈은 둘 다 공짜로
   받는다. ArchUnit 규칙 9·10 이 그 경계를 지킨다.

0-b. **(선결, 판정 완료) `delivery.route-departed` 를 정한다**
   ([ADR-050](adr/ADR-050-route-departure-is-an-event.md), Phase 5-1b 이월 — 2026-09-23).
   tracking 은 `DEPARTED_CAMP` 를 브로커로 내보내지 않는다 — 라우트의 사건을 stop 수만큼 반복하는
   꼴이고, order-service 는 `DISPATCHED` 로 그 구간을 이미 덮는다(§5.4). **정의하는 쪽으로 정했고,
   근거는 ops 화면이 아니라 사실의 가시성이다**: 5-1b 가 출발을 **첫 편차의 출처**로 만들었는데
   (늦은 출발이 가장 흔한 at-risk 원인이고 그 편차는 첫 `ARRIVED` 전에 이미 존재한다) 지금 그
   사실을 아는 것은 tracking 뿐이라, ops 는 첫 `ARRIVED` 가 올 때까지 「출발 안 함」과 「출발했는데
   아직 도착 없음」을 구별하지 못한다. **그 구간이 운영자가 개입할 수 있는 마지막 창이다** —
   아직 안 나간 차는 다시 짤 수 있다. 그리고 `plannedDeparture − departedAt` 은 라스트마일의
   고전 KPI(출발 정시율)라 peak-day 스토리에서 「출발 지연 → at-risk → 재계획」의 첫 칸이 된다.
   **「정의하지 않는다」가 더 단순하다는 것을 알고 취하지 않았다** — 그 단순함의 대가가 운영자
   에게서 마지막 개입 창을 숨기는 것이기 때문이고, 그 문장이 이 결정의 근거다.
   계약은 §4.1 과 ADR-050 에 적었다(`delivery.route-departed.v1`, 키 `routeId`). **스키마·예시·토픽 생성·발행은
   아래 작업 1 에서 한다** — 소비자(ops 의 `rm_routes` 프로젝션)가 먼저 정의하고 tracking 이
   outbox 로 낸다. 소비자 주도를 지키는 것이 이 순서다.
   **그 순서대로 들어왔다**(2026-09-24): 계약·토픽은 ops 프로젝션 PR(#46)에서, 발행은 그 뒤
   tracking 에서. `stopCount` 는 계약에서 뺐다(ADR-050 재검토 지점 3 — 부재를 다른 출처로 메우지
   않는다).

0-c. **(선결, 판정 완료) `route:{id}:progress` 를 지운다** (Phase 5-3 이월,
   [ADR-048](adr/ADR-048-replan-reads-its-own-db.md) 재검토 지점 4 — 2026-09-23 에 닫았다).
   5-5 가 그 키를 채우고 5-3 이 첫 소비자가 될 예정이었으나, §6.8 은 같은 행을 어차피 읽으므로
   캐시를 읽지 않기로 했다 — 같은 사실의 둘째 출처만 생기고 불변규칙 7 이 그것을 진실로 못 쓴다.
   **판정은 6-0 이 표면을 정한 뒤에 났고, 답은 「지운다」다.** 소비자가 생기는 형태로 적어 둔
   것은 「`GET /routes/{id}` 가 실시간 진행 필드를 이 캐시에서 채우고 ops-api 가 위임 조회」였는데,
   6-0 에서 확인해 보니 그 엔드포인트는 **stop 마다 살아 있는 상태를 이미 돌려준다** —
   `nextSeq`·`completed`·`failed` 는 그 응답이 싣고 있는 행들에서 나오므로 캐시를 읽으면 필드가
   아니라 둘째 출처가 는다. 지운 것: `RouteProgressCache` 포트·Redis 어댑터·
   `RouteMutations.progressOf`·`RecordDeliveryStatusService` 의 쓰기 한 줄·`RouteProgressFallbackIT`·
   §7.2 의 행. 폴링이 필요해지면 답은 그 응답을 캐시하는 것이지 다른 키를 두는 것이 아니다.

0. **(선결, 6-0) dispatch OpenAPI 생성물 + 오류·성공 본문 검사.** `contracts/openapi/dispatch-service.yaml`
   과 `OpenApiContractIT` 를 만들고, 오류 본문은 `ProblemDetail`·성공 본문은 이름 있는 타입인지
   본다(§11, `libs/common` 의 `OpenApiResponses`). **ops-api 가 그 문서로 코어 위임 클라이언트를
   만든다** — 작업 1·2 의 입력이다.
   *왜 지금인가*: dispatch 에는 springdoc 이 붙어 있는데 생성물이 없다(2026-09-19 확인). 이것은
   order-service 가 2026-09-19 까지 「404 의 본문은 `OrderView`」라고 **거짓을 말하던** 상태와
   성격이 다르다 — 거짓은 아는 순간 고치지만 **부재는 첫 소비자가 나타나는 시점에 채우는 것이
   소비자 주도 원칙과 맞고**, 그 소비자가 ops-api 다. 반대로 작업 1 을 먼저 하면 위임 클라이언트가
   컨트롤러 소스를 읽고 만들어지고, 그 순간 §11 의 「문서가 계약이다」가 dispatch 에만 성립하지
   않게 된다.
1. ops-api: 전 토픽 프로젝션(§5.5 rm_* 테이블), KPI 시간 버킷 집계, JWT·역할, 커맨드 엔드포인트(웨이브 조기 마감·재계획·stop 재배정·주문 취소·DLQ replay) → 코어 서비스 REST 위임 + `audit_logs`.
   - **(선결, 코드보다 먼저) 프로젝션의 규칙을 ADR 로 적는다**
     ([ADR-051](adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md), 2026-09-23) —
     「먼저 온 사실로 행을 만들고 늦게 온 사실로 채운다 — 부재는 값이 아니다.」
     이 읽기 모델은 토픽 열한 개를 받고 **행 하나에 여러 토픽이 쓴다**(`rm_orders` 에 여섯) —
     축 규칙의 다섯 번째 자리이면서 앞의 넷과 모양이 다른 자리다. 규칙이 말하는 것은 전부
     **하지 않는 일**(칸을 덮지 않고, 재시도하지 않고, 세지 않는다)이라 코드에서 보이지 않고,
     그래서 먼저 적는다. 관측 근거는 작업 4 의 **순서를 뒤섞는 IT** 이고, 그것이 들어오는
     커밋에서 ADR 의 근거 표기를 `추정` 에서 `관측(재현됨)` 으로 바꾼다. **바꿨다**(2026-09-24).
   - **KPI 시간 버킷 — 표가 아니라 뷰 둘**(2026-09-24, §5.5 「KPI — 두 축, 뷰」). V1 의 `rm_kpi_hourly`
     (증감 표)를 V2 가 지우고 `rm_orders` 위에 접수 축(`kpi_intake_hourly`)·배송 축(`kpi_delivery_hourly`)을
     둔다 — 결정 4 의 가장 순수한 형태는 쓰는 쪽이 없는 것이다. 배송 축의 분모에 실패가 들어가려고
     `failed_at` 을 더했고(`delivered_at` 과 배타, 제약으로), 정시율 게이지는 그 뷰의 24 버킷 합이다.
     모집단에서 빠진 수(`outcome_without_promise` → `dawnline_kpi_excluded`)와 갱신 나이
     (`dawnline_kpi_refresh_age_seconds`, 알림은 이 값에)를 함께 낸다 — NaN 은 알림을 울리지 않는다.
     인덱스 둘은 뷰의 버킷 식 그대로다([측정](benchmarks/phase6-kpi-hourly-views-index.md)).
   - **JWT·역할 + 커맨드 위임 + `audit_logs`**(2026-09-24, §5.5 「커맨드 위임」,
     [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md)). 위임 클라이언트는 6-0 의
     생성물이 아니라 **그 문서**에서 빌드 때 만든다 — 채택 기준을 시도 전에 적었고 다섯 다 참이었다. 발급은
     `make token ROLE=…`(12시간), ops-api 는 검증만. 감사 행은 위임 전에 `PENDING`, 결과는 넷(`UNKNOWN` 포함),
     감사 id 는 코어 호출의 상관 헤더와 코어 MDC `auditId` 로. 이번에 붙는 것은 엔드포인트가 이미 있는
     셋(재계획·재배정·취소)이고, 웨이브 조기 마감·outbox 격리는 작업 2 뒤, DLQ 재처리는 따로, **주문 홀드는
     「미구현 — 전이 없음」**(§5.5).
   - **DLQ 재처리**(2026-09-24, §4.6 「DLQ 재처리」, [ADR-053](adr/ADR-053-dlq-replay-is-addressed-to-the-failed-group.md)).
     원래 바이트 그대로(`eventId` 유지 = value 불변) 원래 그룹에게만(`dawnline-replay-for`) — 다른 그룹은
     `libs/messaging` 의 레코드 필터가 **기록 없이** 건너뛴다(`replay_not_target`). 그래서 §4.4 의 「DLQ 30일은
     `processed_events` 14일과 무관하다」가 참이 된다. 감사는 레코드마다 한 행, 재처리는 멱등이라 `UNKNOWN` 은
     다시 누른다(RB-05).
2. 코어 서비스에 필요한 운영 엔드포인트 추가(fulfillment: 웨이브 조기 마감; dispatch: 재계획·재배정은 Phase 3/5에서 존재).
   - **웨이브 조기 마감**(2026-09-24, [ADR-054](adr/ADR-054-early-wave-close-is-an-operator-cutoff.md)) —
     fulfillment 의 첫 REST 표면과 첫 OpenAPI 문서. 컷오프 전에도 닫고 늦은 주문은 이미 있는 개정 경로를
     탄다. `reason` 필수, 마감 원인은 `waves.close_cause`(V3)에 **저장**하고 `promise_revised_total{cause}` 가
     그 칸에서 온다. 마감 본문은 스케줄러와 하나(`WaveClosing`).
   - **outbox 격리 조회·재큐**(2026-09-24, §4.6 「격리 조회·재큐 엔드포인트」,
     [ADR-015 후속 정정](adr/ADR-015-outbox-publish-side-quarantine.md)) — `libs/messaging` 의 공유 코드 한 벌을
     자동 설정이 네 코어에 붙인다(조건: `OutboxRepository` + 서블릿 웹 앱). ops-api 는 속성으로 끄고 그 이유를
     테스트가 말한다(감사 없는 재큐가 생긴다). 재큐는 RB-05 의 SQL 과 같은 조건부 `UPDATE`, 409 는 행의 지금
     위치(`currentState`)를 싣는다. 같은 PR 에서 **OpenAPI 문서를 `integrationTest` 입력으로 거는 일을
     `dawnline.spring-service` 규약으로 옮겼다** — 서비스마다 적으면 다섯째가 또 빠진다.
   - **코어의 운영자 쓰기에 내부 토큰**(2026-09-24, §10 세 층,
     [ADR-055](adr/ADR-055-operator-writes-on-cores-carry-an-internal-token.md)) — 격리 재큐의 재검토 지점을
     네트워크 경계로 미루지 않고 닫았다. 범위는 경로 접두어가 아니라 호출자와 성질(ops-api 만 · 쓰기 · 감사
     대상)이고 규칙은 **기본 거부**다: 면제는 주문 접수·취소와 기사 스캔 셋. `libs/web` 의 매핑 뒤 인터셉터,
     강제 수단은 코어 넷의 `OpenApiContractIT` 가 **문서에서 뽑은** 쓰기를 전부 토큰 없이 부르는 검사. ops-api 의
     기존 위임 셋이 같은 PR 에서 헤더를 싣는다.
   - **ops-api 의 `fulfillment`·`tracking` 위임 그룹**(2026-09-24, §5.5 「커맨드 위임」) — `CLOSE_WAVE`(`reason`
     필수)·`REQUEUE_OUTBOX`·격리 목록(감사 없음). outbox 경로에만 `{service}` 한 칸, 그 밖의 값은 404 이고 감사 행이
     없다. 같은 PR 에서 **내부 토큰 거부 카운터**(`dawnline_internal_token_rejected_total`, 알림 `> 0`)와 RB-07 의
     「다시 누르기가 먼저」.
3. ops-web: 캠프 대시보드, 웨이브/계획 상세(설명 조회 포함), 라우트 지도(Leaflet, 폴리라인·상태 색), 룰 편집.
   **축소안으로 간다**(2026-09-24 결정): 화면 둘 — 캠프 대시보드(웨이브·계획·정시율 두 기준·개정 수·예외 목록)와
   라우트 지도(stop 순서 폴리라인·상태 색·at-risk 강조·재배정·조기 마감). 룰 편집은 Swagger, 설정은 토큰 붙여 넣기 하나.
   PR 셋: C1 ops-api 조회 표면과 OpenAPI 생성물 → C2 ops-web → C3 DoD 를 Compose 스모크에서.
   - **C1 — ops-api 조회 표면**(2026-09-24, §5.5 「조회」). 조회 여섯(캠프·웨이브 창·KPI·예외 목록·웨이브의 라우트·
     라우트 — 마지막은 dispatch 에 조회 위임). stop 좌표는 `rm_routes` 에 두지 않는다(진실은 dispatch, 읽기 모델은
     집계). KPI 조회는 게이지와 같은 창·뷰·식. 창고 좌표 V3(`wave.closed` 의 `depot`). 예외 목록은 창 없이 전부 —
     해소 여부를 모르므로 — 이고 희소 행 부분 인덱스 V4 를 탄다. 나머지 셋에는 인덱스를 더하지 않았다
     ([측정](benchmarks/phase6-ops-read-surface.md)). `contracts/openapi/ops-api.yaml` 과 `OpenApiContractIT` —
     아래 DoD 둘째 줄. 401·403 도 Problem Details.
   - **캠프 코드 — C2 앞의 별도 PR**(2026-09-24). `wave.closed` 에 `campCode`(선택, 같은 major 의 추가) →
     `rm_waves.camp_code`(V5) → `GET /camps` 의 `campCode`. 첫 소비자가 C2 의 대시보드라 C2 전에 들어간다.
   - **C2 — ops-web**(2026-09-24). 클라이언트는 커밋된 `ops-api.yaml` 에서 타입을 받는 얇은 함수(ADR-056 — 후보 1
     `openapi-fetch` 는 기준 5 로 기각, 후보 2 채택). 화면 둘 + 설정. 지도는 기본이 타일 없음, OSM 은 데모용 선택
     (ADR-057, `[결정 필요]` 5 해소). 컴포넌트 테스트: 대시보드 · 예외 목록 · 확인 창의 이유 필수(재배정 창에는 이유 칸
     없음) · 401/403 — 그리고 타일이 전부 실패해도 지도가 남는다. nginx 이미지(`make images` 가 함께 빌드 → CI 스모크의
     빌드 대상), CI `ops-web` job.
   - **C3 — DoD 를 Compose 스모크에서**(2026-09-24). `make demo` 의 셋째 `tools/demo/phase6-demo.sh` — 브라우저 없이
     화면이 부르는 요청을 ops-web 의 nginx 에 보낸다: 토큰 없는 401 · 뷰어의 403 · 운영자 조기 마감(시계를 당기지
     않는다) → 계획과 지도(위임 조회의 좌표) → stop 재배정(두 라우트 revision +1). 진실(dispatch DB) · 감사(audit_logs
     SUCCEEDED) · 읽기 모델을 함께 본다. `Authorization` 과 `X-Dawnline-Audit-Id` 의 통과는 여기가 유일한 검사다.
     재배정은 같은 계획의 다른 라우트로만 되므로 sim 시나리오 `ops-demo`(새벽 하나, 1,200건)가 라우트 둘 이상인 계획을
     만든다 — 데모가 그것을 전제로 확인한다: 마감 **전에** 웨이브 주문 > `max-stops`(필요조건), 마감 뒤에 라우트 수.
     첫 판 600건은 CI 에서 라우트 하나였다(가장 큰 웨이브 81건을 야간 트럭 한 대가 실었다) — 둘째 라우트를 강제하는
     것은 시각도 용량도 아니라 하드 룰 `max-stops`(120) 이었다.
4. 테스트: 프로젝션 멱등(같은 이벤트 2회), **프로젝션 순서 무관(같은 사실을 씨 고정 셔플로 다시 넣어 최종 행이 같은가)**, 권한(viewer가 커맨드 403), 커맨드 감사 기록.
   **멱등과 순서는 다른 것이다** — `processed_events`(불변규칙 2)는 *중복*만 막고 순서에 대해서는 아무것도 말하지 않는다.
   순서 IT 는 대상 토픽을 **빼는 방식**으로 정하고(§13 규칙 2), 셔플이 인과 순서와 실제로 다른지를 첫 어설션으로 말한다
   ([ADR-051](adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md) 결정 6).

**축소안**: ops-web은 대시보드 + 라우트 지도 2화면만. 룰 편집은 Swagger로 대체.

**DoD**
- 운영자가 UI에서 웨이브를 조기 마감하고 계획 결과·라우트 지도를 보며, 특정 stop을 다른 라우트로 옮기는 흐름이 동작.
- **REST 표면이 있는 서비스마다 OpenAPI 생성물과 계약 IT 가 있다**(§11) — 이 Phase 가 끝나면
  dispatch 와 ops-api 둘 다 대상이다. **(2026-09-24) 다섯 다 있다** — dispatch 는 6-0, ops-api 는 묶음 C1.

### Phase 6 마감 대조표

기준일 2026-09-25, `main` = PR #60 머지 시점. 빠진 항목은 **표에 남긴다**.

| # | 작업 | 상태 | 근거 |
|---|---|---|---|
| 0-a | `ProblemDetailsAdvice` → `libs/web` | ✅ | #45 `588ed6a` — [ADR-049](adr/ADR-049-spring-aware-shared-code-lives-in-its-own-lib.md). 넷째(ops-api)는 기반 위에서 시작했다 |
| 0-b | `delivery.route-departed` | ✅ | 계약·토픽 #46 `f2002f3`, 발행 #47 `6b66b5d` — [ADR-050](adr/ADR-050-route-departure-is-an-event.md). `stopCount` 는 뺐다(`18e3906`, 재검토 지점 3) |
| 0-c | `route:{id}:progress` 삭제 | ✅ | `38296b4`(#45) — 소비자가 나타나지 않았다(ADR-048 재검토 지점 4) |
| 0 | dispatch OpenAPI 생성물 + 계약 IT | ✅ | #45 |
| 1 | 프로젝션 규칙 + 전 토픽 프로젝션 | ✅ | #46 — [ADR-051](adr/ADR-051-first-fact-creates-the-row-absence-is-not-a-value.md), 근거 표기 `관측(재현됨)` |
| 1 | KPI 시간 버킷(뷰 둘) · 정시율 게이지 | ✅ | #48 `7283caa` — Phase 5 DoD 의 「정시율 집계」 빈칸이 여기서 닫혔다 |
| 1 | JWT·역할 · 커맨드 위임 · `audit_logs` | ✅ | #49 `e0b7516` — [ADR-052](adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md) |
| 1 | DLQ 재처리 | ✅ | #50 `1d70fe8` — [ADR-053](adr/ADR-053-dlq-replay-is-addressed-to-the-failed-group.md) |
| 1 | 주문 홀드 | ⬜ **미구현** | 주문 상태 머신에 전이가 없다(§5.5). 전이를 만드는 것은 order-service 의 설계 변경이다 |
| 2 | 웨이브 조기 마감 | ✅ | #51 `4a39957` — [ADR-054](adr/ADR-054-early-wave-close-is-an-operator-cutoff.md) |
| 2 | outbox 격리 조회·재큐 | ✅ | #52 `94c8ab2` — [ADR-015 후속 정정](adr/ADR-015-outbox-publish-side-quarantine.md) |
| 2 | 코어 운영자 쓰기의 내부 토큰 | ✅ | #53 `75338d4` — [ADR-055](adr/ADR-055-operator-writes-on-cores-carry-an-internal-token.md) |
| 2 | ops-api 의 fulfillment·tracking 위임 | ✅ | #54 `468113f` |
| — | (이월 정리) 없는 시계열 §9.1 · 알림 카운터 사전 등록 | ✅ | #55 `83c6fcb` · #56 `a47de5a`. 남은 하나(`cancel_too_late{camp}`)는 7-1 |
| 3 | C1 조회 표면 + `ops-api.yaml` | ✅ | #57 `7679c3b` |
| 3 | 캠프 코드 | ✅ | #58 `6fe3a2f` — 변경은 반영되지 않는다(§5.5, #61) |
| 3 | C2 ops-web | ✅ | #59 `90f5e32` — [ADR-056](adr/ADR-056-ops-web-client-is-typed-from-the-committed-contract.md) · [ADR-057](adr/ADR-057-map-draws-without-tiles-ops-web-is-an-nginx-image.md). `shortId` 결함은 #60 에서 고쳤다(§13 축 11) |
| 3 | C3 DoD 를 Compose 스모크에서 | ✅ | #60 `673449d` — 첫 판(600건)은 라우트 하나였다: 둘째 라우트를 강제하는 것은 `max-stops` 이고 전제는 마감 전에 본다 |
| 3 | 웨이브/계획 상세(설명 조회) | ⛔ **축소안으로 제외** | 설명은 dispatch `GET /plans/{id}` 가 낸다 |
| 3 | 룰 편집 화면 | ⛔ **축소안으로 제외** | Swagger |
| 4 | 프로젝션 멱등 | ✅ | #46 `ProjectionListenerIT` |
| 4 | 프로젝션 순서 무관 | ✅ | #46 `ProjectionShuffleIT`. 비교 칸이 채워졌는지 먼저 묻는 것은 #58(§13 축 10) |
| 4 | 권한(뷰어 403) · 감사 기록 | ✅ | #49 `OpsCommandIT` |
| — | 후속 문서 · IT 경합 | ✅ | #61 `d41c6a0`(§13 축 10 · §5.5 캠프 코드 · 예시 `eventId`) · #62 `75ebaa5`(`OutboxLeaderLockIT` — 근거 `재현 시도했으나 실패`, CI 1회) |

| DoD | 상태 | 근거 |
|---|---|---|
| UI 경로로 조기 마감 → 계획·지도 → stop 재배정 | ✅ | `make demo` 의 `tools/demo/phase6-demo.sh`, CI Compose 스모크. ops-web 의 nginx 를 지나 화면과 같은 경로를 부른다 |
| REST 표면이 있는 서비스마다 OpenAPI 생성물 + 계약 IT | ✅ | 다섯 다 — dispatch #45, ops-api #57 |

**대조표가 잡은 것 셋**

1. **[ADR-012](adr/README.md)(CQRS 읽기 모델을 ops-api 에 집중)는 여전히 「⏳ Phase 6 예정」이다** — 결정은 이 Phase
   에서 구현됐고(ADR-051 이 그 규칙이다) 문서는 쓰이지 않았다. 7-6 「ADR 전체 확정(001–012)」이 그 자리이고,
   거기서 005·010(각각 Phase 2·3 예정으로 남아 있다)과 함께 쓴다.
2. **보존 정책이 이 Phase 에서 정해지지 않았다.** [ADR-045](adr/ADR-045-revision-comparison-is-per-route.md) 는
   `route_revisions`·`shipments` 의 보존을 「배송 이력의 보존을 정하는 Phase 6」에 맡겼는데, 이 Phase 는 `rm_*` 에도
   보존을 두지 않았다(§5.5 — 인덱스 판단들이 「보존 정책이 없으므로」를 전제로 적었다). 알고 둔 것과 맡겨진 것이
   섞여 있어, 주인은 Phase 7-0 표가 정한다.
3. **테스트가 결함을 기대값으로 들고 있었다.** C2 의 대시보드 테스트는 `shortId` 의 결함 있는 출력을 고정했고,
   드러낸 것은 컴포넌트 테스트가 아니라 C3 의 스모크 출력이었다 — §13 축 11.

**재검토 지점으로 남은 것**

| 지점 | 다시 여는 조건 | 기록 |
|---|---|---|
| 예외 목록의 해소 표시 | 해소 사실(환불·회수)이 이벤트로 생길 때 | §5.5 |
| `cancel_too_late_total{camp}` 알림식 | 7-1 의 규칙 파일 | §9.1, Phase 7-1 (a) |
| `UNKNOWN` 자동 해소 | `UNKNOWN` 이 사람이 따라가기 어려운 빈도로 나타날 때 | ADR-052 재검토 지점 4 |
| 캠프 코드 변경 미반영 | 캠프 참조 데이터가 변경 이벤트를 가질 때 | §5.5 |

---

## Phase 7 — 신뢰성·관측성·문서 마감 (Staff 차별화)

> **Phase 1 실측이 연 항목 — 콜드 스타트** (2026-09-05, `docs/benchmarks/phase1-orders-k6.md` 6절)
>
> 기동 직후 약 80초 동안 `POST /orders` p99 가 **2~4초**이고 레이트 리밋이 fail-open 으로
> 우회된다. **재현된다**(컨테이너 재시작 후 동일). 원인 사슬은 하나다 —
> `SERVICE_CPU_LIMIT=0.75` → JVM 이 G1 대신 **SerialGC** 선택 → 클래스 27k 로딩 중 Metaspace
> 임계로 full GC **166회·17.11초** → 요청이 커넥션을 **3.07초**까지 점유 → 풀(10) 포화
> (`pending` 191) → Redis 왕복도 50 ms 예산 초과 → 차단기 개방.
>
> 웜에서는 같은 구성이 p99 **4.8~48 ms**, `pending=0` 이다. 즉 **정상 상태의 문제가 아니라
> 배포 창의 문제**이고, 롤링 배포 중 새 인스턴스가 트래픽을 받는 구간에 그대로 나타난다 —
> §8.6 의 레디니스는 "뜰 준비" 만 보고 "빠를 준비" 는 보지 않는다.
>
> **후보 대응**: CPU 한도 상향(2 이상 → G1) · AppCDS · `-XX:TieredStopAtLevel` 조정 ·
> `minimum-idle` 상향 · 기동 후 워밍업 요청 · 레디니스에 워밍업 포함.
> **지금 고르지 않는다** — 어느 것을 고를지는 §8.6(기동·종료)과 §8.2(자원 한도)를 함께 봐야
> 하고, 그 둘이 Phase 7 의 주제다. 수치와 원인만 여기 남긴다.

### 7-0 이월 대조표 — Phase 7 이 판정할 것 (2026-09-25)

**Phase 7 은 새 기능이 아니라 이월된 조건들이 판정되는 곳이다.** 그 조건은 기억이 아니라 **전부에서 빼는
방식으로** 뽑는다. 원천은 셋: ① 저장소 전체의 「Phase 7」 표기(`git grep -n 'Phase ?7'` — `75ebaa5` 기준 25개 파일 87줄)
② ADR 52개의 재검토 지점 전부(제목에 없는 것은 본문의 「재검토」·「다시 연다」로) ③ 이 계획서의 열린 표기
(⚠️ · ◐ · ⬜ · ⏸). 뽑은 행 가운데 Phase 7 에서 판정하지 않는 것은 **표 C 에 이유와 함께** 남긴다 —
없으면 「검토했는데 뺀 것」과 「못 본 것」을 구별할 수 없다. 닫히는 곳의 번호는 아래 **작업** 목록의
번호다(7-1 = 작업 1). **D 의 결정은 2026-09-25 에 받았다** — 그 결정이 A·B 의 닫히는 곳을 바꾼 자리는 행에 반영했다.

**A. Phase 7 로 적혀 있는 것**

| # | 항목 | 출처 | 여는 조건 | 판정 데이터 | 닫히는 곳 |
|---|---|---|---|---|---|
| A1 | 전역 `Bulkhead`(+ Resilience4j 도입 판단) | Phase 1 이월 · §8.3 판정 기록 · §11 기술 스택 표 | **웜** 상태에서 `hikaricp_connections_pending` > 0 이고 원인이 풀 포화 | peak-day 의 `pending`·주문 API p99 | 7-4 |
| A2 | 콜드 스타트(기동 80초 p99 2~4초, 재현됨) | 위 인용 · `phase1-orders-k6.md` 6절 | 이미 켜졌다 — 코드가 아니라 **측정 조건**이다(D1) | peak-day 를 콜드 스택에서 시작해 첫 계획·첫 소비 처리량을 정상 상태와 갈라 적는다 | 측정 7-4 · 예열 항목 7-5(RB-06) |
| A3 | lag-aware grace | Phase 2 이월 · ADR-020 결정 5 | `dawnline_promise_revised_total` 이 컷오프 직후 **뭉친다** | 그 카운터의 시간축 | 7-4 |
| A4 | 4-20 구성의 수락 기준 · ADR-043 「`small` 회복」 · ADR-004 조건 (a) | Phase 4 대조표 20 · ADR-043 ② · ADR-044 ③ · ADR-004 | `small` 에서 `savings-cw+ls` ≤ 기본 전략 — **다섯 데이터셋 전부** | peak-day 의 `peak` 규모 웨이브 + 벤치마크 다섯 | 7-4 |
| A5 | `cancel_too_late_total{camp}` 알림식 | §9.1 「없는 시계열」 · Phase 6 이월 | 라벨이 열린 집합 — 식이 첫 표본을 증가로 읽는다 | 규칙 파일 + 컨테이너 | 7-1 |
| A6 | §9.4 알림 규칙 전체 · 대시보드 4종 · Phase 5 카운터 넷의 패널 | §9.4 · 7-1 · `deploy/compose` 의 빈 자리 셋 | — (산출물) | — | 7-1 |
| A7 | `increase()` 가 1 로 태어난 시계열의 첫 증가를 못 읽는다 — 근거를 `관측(재현됨)` 으로 | §9.1 | 미리 등록한 카운터와 안 한 카운터를 나란히 | 규칙 파일 + 컨테이너(음성 표본) | 7-1 |
| A8 | KPI 알림 두 개의 초기값(`kpi_refresh_age` > 300 · `promise_unknown` 30분) | §9.4 「초기값 — peak-day 에서 재검토」 | peak-day 에서 오탐·미탐 | 두 게이지의 시간축 | 식 7-1 · 값 7-4 |
| A9 | `STALE_PLACED` — 단위만(⚠️) | Phase 2 대조표 | 재처리 경로가 생겼다(ADR-053) — 24시간 넘은 `order.placed` 가 브로커로 다시 올 수 있다 | 재처리를 지나는 IT | 7-3 |
| A10 | `late-injection` → at-risk → 재계획 → revision 의 compose 전 구간(◐) · 5-4 의 두 서비스 잇기(◐) | Phase 5 대조표 · DoD | — (빈칸) | 로그·DB | 7-4 |
| A11 | `rules:camp` 룰셋 캐시(⬜) | Phase 3 대조표 · `adapter/out/redis/package-info.java` | 부분 재계획이 룰을 라우트마다 읽어 룰 조회가 **보일 때** | 계획당 룰 조회 수·시간 | 7-4 |
| A12 | ADR-008 확정 · 4-3 병렬화 이월 · ADR-035 게이트 | Phase 4 대조표 3·7 · ADR-035 | `peak` 에서 예산이 물리고 잘림의 대가 ≥ 1% | peak-day 의 계획 시간·열화 사유 | 데이터 7-4 · 문서 7-6 |
| A13 | ADR-004 한정 실행(`timefold` · `medium` 한 개, 선택) | ADR-004 결정 4 | 7-6 에 여유가 있을 때 · 공정성 셋 | 벤치마크 | 7-6 |
| A14 | 쓰이지 않은 ADR 넷 — 005(Phase 2 예정) · 010(Phase 3 예정) · 011(Phase 7 예정) · 012(Phase 6 예정) | `docs/adr/README.md` · §16 | — (「001–012 확정」) | — | 7-6 |
| A15 | 카오스 셋 + 검증 SQL · ADR-027 의 `chaos-redis` 기준(**발행이 멈추지 않고 지연도 오르지 않는다** — 후속 정정의 기준) | 7-3 · ADR-027 재검토 지점 | — | 검증 SQL 세 줄 · `outbox_lag` | 7-3 |
| A16 | 리더 합이 **항상 1** — 인스턴스 둘 이상 | ADR-027 | 인스턴스를 실제로 둘 이상 올릴 때 | `dawnline_outbox_leader` 의 인스턴스 합 | 7-4 |
| A17 | `FOR SHARE` 의 multixact | ADR-025 | peak-day 버스트 | `pg_stat_slru` multixact · 락 대기 | 7-4 |
| A18 | `cancel_too_late_total` ≠ 0 이면 창의 폭이 가정을 넘은 것 | ADR-026 | peak-day 에서 0 이 아니다 | 그 카운터 + order-service 의 `order.dispatched` 랙 | 7-4 |
| A19 | `UNKNOWN` 자동 해소 | ADR-052 재검토 지점 4 | `UNKNOWN` 이 사람이 따라가기 어려운 빈도 | `dawnline_ops_commands_total{result="UNKNOWN"}` — peak-day 에서는 구조적으로 0 이다. **카오스 중의 커맨드가 낸다**(D3, 인위 주입 없음) | 7-3 |
| A20 | 출발 정시율 | ADR-050 재검토 지점 1 · §4.1 「peak-day 스토리의 첫 칸」 | `plannedDeparture − departedAt` 이 크고 at-risk 와 상관 | 그 차의 분포 | 7-4 |
| A21 | 한 traceId 로 네 서비스 span · Tempo `metrics_generator`(서비스 그래프) | 7-2 · `deploy/compose/README.md` | — | 스크린샷 | 7-2 |
| A22 | 런북 — **계획서의 「RB-01~06」은 낡았다**: RB-05 는 있고(Phase 6) RB-07 이 §9.5 에 있다 | 7-5 · §9.5 | — | — | 7-5 (RB-01~04 · 06 · 07 — 작업 5 를 고쳤다) |
| A23 | 포스트모템(가상 장애, 실제 측정치 기반) | 7-5 | 7-4 의 수치 | 7-4 리포트 | 7-5 |
| A24 | README — 그림 · 데모 GIF · Tempo 스크린샷 · 피크 SLO 표 · 카오스 결과 · 정시율 리포트 | README 「측정해서 채울 자리」 넷 | — | 7-2 · 7-3 · 7-4 | 7-6 |
| A25 | release.yml(GHCR · SBOM) · CI 의 「컨테이너 이미지 빌드 (Phase 7)」 job · **ops-web 의 nginx 이미지는 따로**(ADR-057) | 7-7 · `ci.yml` · ADR-057 | — | — | 7-7 |
| A26 | (선택) `deploy/k8s` + kind 스모크 · ADR-011 static membership | 7-7 · ADR-011 | — | — | 7-7 |
| A27 | **peak-day 의 전제** — 시나리오가 없다(`scenarios.yml` 은 smoke · tiny · ops-demo · late-injection), sim-runner 이미지는 꺼져 있다(「Phase 7 피크에서 다시 켠다」), `make peak` 은 자리표시다. 그리고 **부록 A 의 목록과 `scenarios.yml` 이 어긋난다** — `tiny`·`ops-demo` 는 목록에 없고, `late-injection` 은 목록이 「지연 15% · 실패 3%」, 파일이 `delay-probability: 1.0` · `failure-probability: 0.05` 다 | `tools/sim-runner` · `Makefile` · 부록 A | — | — | 7-4a |
| A28 | 사건은 지나갔고 재검토 기록이 없는 셋 — ADR-029 ①(4-1 이후 예산 배분) · ②(부분 저장의 배치 단위) · ADR-047 ④(relocate 가 돌기 시작한 뒤 「덮음」의 빈도) | 표 C 에 있던 행 | — (소급) | 판정이 다른 곳에 있는지부터 찾는다 | 7-6 |
| A29 | `cause="manual"` 이 일상이 되는가 | ADR-054 재검토 지점 1 | 조기 마감이 드문 결정이라는 가정이 틀렸다 | `promise_revised_total{cause}` · 감사 행 — peak-day 가 정해진 시각에 커맨드를 섞으므로(D3) 0 이 아니다 | 7-4 |

**B. Phase 7 표기는 없지만 peak-day 가 판정 데이터를 내는 것** — 적어 두지 않으면 7-4 가 그 수를 재고도 판정하지 않는다

| # | 항목 | 출처 | 판정 데이터 | 비고 |
|---|---|---|---|---|
| B1 | `no-anchor` 빈도 — 「메운 뒤 얼마나 줄었나」 | ADR-048 ① · ADR-050 ② | `dawnline_replan_total{outcome="no-anchor"}` | 잦으면 dispatch 가 `route-departed` 의 소비자가 된다 |
| B2 | 편차 대조 허용 오차 60초 | ADR-048 ② | `dawnline_at_risk_deviation_mismatch_total` | 늘 0 이면 넓고, 늘 오르면 값이 뜻을 잃는다 |
| B4 | relocate 탐색 상한에 닿는가 | ADR-048 ⑤ | `dawnline_replan_total{outcome="truncated"}` + 설명 행의 `searchTruncated`(D4 — 7-4a 에서 만든다) | 「이득이 없다」와 「다 못 봤다」가 `no-gain` 한 라벨에 접혀 있었다 |
| B5 | `scan_after_cancel` 두 자리가 같은 비율로 오르는가 | ADR-047 ③ | `dawnline_scan_after_cancel_total` | 같으면 라벨이 아니라 이름을 가른다 |
| B6 | 함대 규모 대 §8.1 물량 — 캠프당 20대면 차량당 187 stop | ADR-030 | peak-day 는 하루 15만 건 — **그 물량 자체다** | **D2 로 정했다** — 둘로 돈다(peak-day 는 80% 기준의 함대, overload-day 는 함대 그대로). 부록 A |
| B7 | 교대 공백(08–09 · 22–23)에 계획이 도는 빈도 | ADR-030 | 계획 시각 분포 | |
| B8 | 1단계 라우트 수가 2단계 경계(`peak` 410)에 가까운가 | ADR-044 ① | 계획당 1단계 라우트 수 | 벤치마크 `peak` 에서 216 |
| B9 | 후보가 10,000 건을 넘는가 — 넘으면 프로젝션 읽기를 다시 잰다 | ADR-029 ③ | 웨이브당 후보 수 | |
| B10 | FAST 첫 단의 대가 — 재삽입 한 번을 더할지 | ADR-041 ① · ADR-043 ④ | FAST 전환 횟수(7-4 가 이미 잰다) × 그 계획의 비용 | 여유(slack) 그림자는 **닫혔다**(표 C) — 이 행은 그것과 다른 물음이다 |
| B11 | 보존 인덱스 둘의 쓰기 대가 — `updated_at` 이 인덱스 키라 HOT 갱신을 잃는다 | ADR-058 · 7-0b [측정](benchmarks/phase7-retention-indexes.md) §1.4 · §2.4 | peak-day 동안 `shipments` · `rm_orders` 의 `n_tup_hot_upd / n_tup_upd`(`pg_stat_user_tables`) | 7-0b 측정은 채운 직후라 인덱스 없이도 HOT 0 이었다 — 운영 모양의 몫은 **근거: 추정**. 크면 BRIN 을 다시 잰다(PostgreSQL 16 릴리스 노트: BRIN 칸만 바뀌는 갱신은 HOT 을 허용한다 — 이 저장소에서 재지 않았다) |

**C. 뺀 것 — Phase 7 에서 판정하지 않는 재검토 지점**

| 이유 | 항목 |
|---|---|
| **이미 닫혔다** | **Phase 3 대조표의 `PRIORITY_BOOST` 계약 결손**(⚠️ — 처음 판에는 D6 으로 적었다. Phase 4-11 이 계약 변경 없이 닫았다: 우선도는 받은 사실에서 파생한다, ADR-028 · `LoadCandidateService`. Phase 3 행만 갱신되지 않았다 — 지금 고쳤다) · ADR-015 ①(ADR-055) · ADR-049 ②(규칙 9 가 조건이라는 증명 — #45 의 음성 표본 `OwnShapeAdvice` 가 했다. ops-api 로 한 번 더 하지는 않았다) · ADR-023 의 DLQ 경로 조건(ADR-053 이 §4.4 의 의존 경고로 답했다) · ADR-028 ①②(ADR-033 의 통합 키와 80% 기준) · **ADR-033 「`peak` 은 아직 재지 않았다」**(`DatasetFeasibilityTest` 가 2026-09-12 부터 빼는 방식으로 `peak` 을 포함한다 — ADR 의 문장만 남았다) · ADR-043 ① · ADR-047 ①⑤ · ADR-048 ④ · ADR-050 ③ · ADR-051 ①②③ · ADR-052 ② · **FAST 클러스터 여유 그림자**(`phase4-strategies.md` §7.3 「판정: 닫는다」 — 이득이 비단조. 커밋되지 않은 probe 의 수라는 ⚠️ 가 그 절에 있다) |
| **사건 조건 — 일정이 없다**(요구·규모·버전이 바뀌는 날) | ADR-004 (b)(c) · ADR-013 · ADR-015 ② · ADR-028 ④ · ADR-030 ①(로스터 모델) · ADR-031 · ADR-032 · ADR-034(대안 표의 재검토 조건 둘) · ADR-035 ② · ADR-036 · ADR-037 · ADR-038 · ADR-039 · ADR-040 · ADR-041 ②③ · ADR-042 · ADR-043 ③⑤ · ADR-044 ②④ · ADR-045 ②(분할 배송) · ADR-047 ② · ADR-049 ① · ADR-052 ①③ · ADR-053 · ADR-054 ② · ADR-055 · ADR-056(생성기 버전) · §17 `[결정 필요]` 4(Valkey) · §5.5 `rm_routes` 100만 행 |
| **조건이 켜지지 않았다** | `phase1-orders-k6.md` 판정표의 「`outbox_lag` 상승 → Phase 7 로 넘길지」 행 — Phase 1 의 미달은 콜드 스타트 하나였다 |
| **메커니즘 조건 — 7-4 의 수가 연다** | ADR-048 ③ 같은 지점으로는 옮기지 않는 규칙(처음 판의 B3, D4) — 후보 한 칸을 건너뛰는 자리라 트리거 단위 outcome 이 아니다. **peak-day 에서 `no-gain` 이 `applied` 보다 잦으면 연다** |
| **범위 밖으로 닫았다**(D 의 결정) | 재배송 +3(D7 — DESIGN §6.3 우선도 표 · ADR-028 · Phase 5 대조표에 행을 더했다) |

**D. 주인 없는 열린 항목 — 결정** (2026-09-25)

| # | 항목 | 출처 | 결정 |
|---|---|---|---|
| D1 | 콜드 스타트 | A2 | **코드가 아니라 측정 조건이다.** peak-day 는 콜드 스택에서 시작하고 첫 계획·첫 소비 처리량을 정상 상태와 갈라 적는다(7-4 의 측정 헤더 한 줄). RB-06 에 예열 항목(7-5) |
| D2 | 함대 규모 대 §8.1 물량 | ADR-030 · B6 | **둘로 돈다** — Phase 4 의 `peak`/`overload` 분리를 시나리오로. `peak-day` 는 실현 가능성 기준(80%)이 정하는 함대(성수기 증차), `overload-day` 는 같은 물량을 함대 그대로. 대수는 고르지 않고 기준이 낸다(부록 A) |
| D3 | 운영자 없는 시뮬레이션 | A19 · A29 | **넣되 최소로.** peak-day 중 정해진 시각에 조기 마감·재배정을 몇 번(스크립트). `UNKNOWN` 은 peak-day 가 아니라 **7-3 카오스가 낸다** — 인위 주입은 하지 않는다 |
| D4 | 셀 칸이 없는 둘 | ADR-048 ③ · ⑤ | **relocate 상한(⑤)**: 평가 상한 2,000회에 걸려 이동을 하나도 못 찾으면 지금은 `no-gain` 으로 접힌다 — 판정 불가를 값으로 접지 않는다. `dawnline_replan_total{outcome}` 에 **`truncated`** 를 더한다(합이 트리거 수라는 성질은 그대로). 이동을 찾았는데 상한에 걸린 경우는 `applied` 로 두되, **그 계획의 설명 행(`AT_RISK_RELOCATE`)에 `searchTruncated: true`** — 「왜 이 이동인가」에 「더 좋은 이동을 못 본 채 고른 것」이 붙어야 §6.3 의 설명이 정직하다. 메트릭은 늘리지 않는다. 7-4a. **같은 지점 제외(③)**: 후보 한 칸을 건너뛰는 자리라 outcome 의 모양이 아니다 — 표 C, 여는 조건은 「`no-gain` 이 `applied` 보다 잦을 때」(메커니즘 조건) |
| D5 | 보존 정책 | ADR-045 · ADR-047 · §5.5 | **7-0b** 로 7-1 앞에 — ADR-023 의 두 축 그대로: `shipments` 30일(`updated_at`), `route_revisions` 90일(상위, 삭제 순서는 두 기간이 보장하되 `NOT EXISTS` 가드), `rm_*` 90일(조사 가능성 — 예외 목록의 상한). 정리 배치는 기존 패턴, 인덱스는 EXPLAIN. **→ [ADR-058](adr/ADR-058-shipment-and-read-model-retention.md)** (2026-09-25 승인 — 덧붙은 넷: 보존 표 ↔ 설정 기본값 대조 · 모든 정리의 `dawnline_retention_last_success_age_seconds{table}` · 비종결 `rm_orders` 는 세고 365일 상한 · dispatch 는 7-0c 에서 `plan_explanations` 30일. `audit_logs` 무기한) |
| D6 | ~~`PRIORITY_BOOST` 계약 결손~~ | Phase 3 대조표 | **잘못 뽑은 행이었다** — Phase 4-11 에서 이미 닫혔다(표 C) |
| D7 | 재배송(+3) | ADR-028 · §6.3 | **범위 밖, 미구현으로 기록.** 세 서비스를 가로지르는 새 흐름이고 가중치의 사실 출처는 그 흐름이 생겨야 나온다 |
| D8 | 시나리오 `normal-day` · `cold-heavy` | 부록 A | **`normal-day` 는 필수**(피크의 수치는 평일 열 옆에서 읽힌다). `cold-heavy` 는 `cold-ratio` 변형이라 포함 — 코드가 필요해지면 뺀다. 7-4a |

**원천 목록 — 「Phase 7」 표기가 있는 파일과 그 줄 수** (`CarryOverLedgerConsistencyTest` 가 저장소와 대조한다)

세는 규칙: 정규식 `Phase ?7` 에 맞는 **줄**의 수. 저장소 루트부터 전부 읽되 셋을 뺀다 — ① 이 계획서의 「Phase 7」 절
(이 표가 사는 자리다) ② 검사 자신의 소스(규칙을 설명하느라 그 말을 쓴다) ③ 빌드 산출물 · 숨은 디렉터리(`.github` 는
읽는다) · 로컬 전용 `.env`. 원천 ②(ADR 의 재검토 지점)는 파일 단위로 대조한다 — 본문에 「재검토」가 있는 ADR 은
이 절 어딘가에 `ADR-NNN` 으로 나와야 한다. 원천 ③(Phase 대조표의 열린 표기 ⚠️ ◐ ⏸)은 **상태 칸 단위로** 대조한다
— 계획서(이 절 밖)와 Phase 4 의 DoD 대조가 사는 `docs/benchmarks/phase4-strategies.md` §8 의 표에서, 머리에 「상태」
칸이 있는 표의 그 칸이 열린 표기를 들고 있으면 **닫힘(✅ · ⛔ · ❌)으로 시작하거나 `→ 7-0 A9` 처럼 이 절의 행을
가리켜야** 한다. 이것이 없어서 Phase 3 의 계약 결손이 「결정 필요」(처음 판의 D6)로 올라왔다 — 다른 Phase 에서 닫히면서
원래 표의 칸이 갱신되지 않은 행이었다. 이 검사를 처음 돌렸을 때 같은 모양이 둘 더 나왔다(Phase 1 의 k6 「스크립트만」,
Phase 3 의 §6.10 넷째 분기). ⬜(미구현)는 대상이 아니다 — 대조표 규칙이 「빠진 항목은 미구현으로 남긴다」이므로 그
표기는 열린 약속이 아니라 **최종 기록**일 수 있고, 그중 Phase 7 로 미룬 것(`rules:camp`)은 그 칸의 「Phase 7」 표기로
원천 ①이 잡는다.

| 파일 | 줄 | 행 |
|---|---|---|
| `.github/workflows/ci.yml` | 4 | A25 |
| `Makefile` | 5 | A15 · A27 |
| `README.md` | 8 | A4 · A22 · A23 · A24 · A27 |
| `deploy/compose/README.md` | 3 | A6 · A21 |
| `deploy/compose/docker-compose.yml` | 1 | A6 |
| `deploy/compose/grafana/provisioning/dashboards/dashboards.yml` | 1 | A6 |
| `deploy/compose/grafana/provisioning/datasources/datasources.yml` | 1 | A21 |
| `deploy/compose/prometheus/prometheus.yml` | 2 | A6 |
| `deploy/compose/tempo/tempo.yml` | 1 | A21 |
| `docs/DESIGN.md` | 12 | A1 · A2 · A5 · A7 · A8 · A13 · A14 · A26 · D2 · D7 |
| `docs/IMPLEMENTATION_PLAN.md` | 20 | A1 · A2 · A3 · A4 · A5 · A9 · A10 · A11 · A12 · A13 · C · D5 · D7 |
| `docs/adr/ADR-004-compare-against-the-boundary-not-another-solver.md` | 5 | A4 · A13 · C |
| `docs/adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md` | 3 | A3 |
| `docs/adr/ADR-025-wave-admission-share-lock.md` | 1 | A17 |
| `docs/adr/ADR-026-dispatch-cancellation-window.md` | 2 | A18 |
| `docs/adr/ADR-027-outbox-relay-leader-lock.md` | 4 | A15 · A16 |
| `docs/adr/ADR-028-unassigned-policy.md` | 1 | D7 |
| `docs/adr/ADR-033-constraint-classes.md` | 2 | C |
| `docs/adr/ADR-052-delegation-client-is-generated-from-the-committed-contract.md` | 1 | A19 |
| `docs/adr/ADR-057-map-draws-without-tiles-ops-web-is-an-nginx-image.md` | 1 | A25 |
| `docs/adr/README.md` | 3 | A13 · A14 |
| `docs/benchmarks/phase1-orders-k6.md` | 6 | A1 · A2 · C |
| `docs/benchmarks/phase4-strategies.md` | 2 | A4 |
| `services/dispatch-service/src/main/java/com/dawnline/dispatch/adapter/out/redis/package-info.java` | 1 | A11 |
| `tools/demo/phase2-demo.sh` | 1 | A2 |
| `tools/sim-runner/build.gradle.kts` | 1 | A27 |


**작업** (순서 2026-09-25 확정 — 7-0 → 7-0b → 7-0c → 7-1 → 7-2 → 7-3 → 7-4a → 7-4 → 7-5 → 7-6 → 7-7)

**대시보드가 peak-day 앞에 오는 이유**는 그 실행이 패널을 검증하는 첫 실행이어야 하기 때문이고, **카오스가 앞에
오는 이유**는 검증 SQL 을 peak-day 가 다시 쓰기 때문이다.

0. **이월 대조표**(위) — 그리고 마지막 커밋으로 **대조 검사**: 표의 「원천 목록」과 저장소의 「Phase 7」 표기, 표와
   재검토 지점이 있는 ADR 을 서로 비춘다(`CarryOverLedgerConsistencyTest`). 항목을 닫는 PR 은 표를 같이 고쳐야 초록이
   된다 — ADR 인덱스 검사가 하는 일과 같다.
0b. **보존 정책**(D5, [ADR-058](adr/ADR-058-shipment-and-read-model-retention.md)) — `shipments` 30일 · `route_revisions` 90일 ·
   `rm_*` 90일, ADR-023 의 두 축과 기존 정리 배치 패턴. 순서 그대로: ① 설계(§7.1 **보존 표** — 흩어진 여섯 자리를 한 표로)와
   ADR ② 마이그레이션 둘(tracking `V3` `shipments.updated_at` · ops-api `V6` `rm_*.updated_at NOT NULL`, 기존 행은 `now()`)
   ③ 정리 배치 셋(`shipments` · `route_revisions` · `rm_*` — 클래스로는 서비스마다 하나) ④ 운영 크기 EXPLAIN(`shipments` 450만 · `rm_orders` 1,350만 ·
   `route_revisions`·`rm_routes` 11만 · `rm_waves` 3,600) → `docs/benchmarks/phase7-retention-indexes.md`. **함께 세우는 것**:
   모든 정리의 `dawnline_retention_last_success_age_seconds{table}`(기존 여섯 포함 — §9.4 알림 2일), 비종결 `rm_orders` 의
   `dawnline_rm_orders_stuck` 과 365일 상한, 보존 표와 설정 기본값의 대조 검사(모듈마다 `RetentionTableDefaultsTest` ·
   `libs/common` 의 `RetentionTableConsistencyTest`).
0c. **dispatch 보존**(ADR-058 결정 9) — `dispatch_candidates` · `plan_explanations` 30일(설명은 조사 데이터 — 90일이면
   1,800만 행), `route_plans` · `routes` · `route_stops` · `route_stop_orders` 90일. FK 사슬 순서대로 지우고, 운영 크기
   EXPLAIN 과 함께. 보존 표에 행이 들어오고 대조 검사가 그 행을 본다.
1. Grafana 대시보드 4종 JSON, Prometheus 알림 규칙(§9.4) 커밋.
   **없는 시계열 둘을 여기서 닫는다**(2026-09-24 이월, §9.1 「없는 시계열은 0 으로 보인다」):
   (a) `dawnline_cancel_too_late_total{camp}` 의 알림은 라벨이 열린 집합이라 미리 등록할 수 없다 — 규칙 식이
   새 시계열의 첫 표본을 증가로 읽게 쓴다. (b) `increase()` 가 1 로 태어난 시계열의 첫 증가를 못 읽는다는 것을
   규칙 파일과 함께 컨테이너에서 **한 번 재현하고** §9.1 의 근거를 「관측(재현됨)」으로 올린다 — 미리 등록한 카운터와
   등록하지 않은 카운터를 나란히 두면 음성 표본이 된다.
   **Phase 5 가 만든 카운터 넷은 여기서 패널이 된다**(2026-09-23 이월 — 계기는 5-3·5-5 에 있었고
   대시보드는 이 Phase 다). `dawnline_replan_total{outcome}`(다섯 갈래를 **쌓아** 그린다 — 합이
   트리거 수라는 것이 한눈에 보여야 한다) · `dawnline_at_risk_deviation_mismatch_total` ·
   `dawnline_status_after_relocate_total` · `dawnline_scan_after_relocate_total`.
   **뒤의 셋은 한 패널에 겹쳐 놓는다** — 쌍이 *갈리는 것*이 정보이기 때문이다(§9.1):
   relocate 둘은 「기사가 옛 계획으로 찍었다」(tracking) 대 「그 사실이 dispatch 에 닿았다」로
   갈리고, mismatch 는 그 둘 중 어느 쪽 랙인지를 좁힌다. 따로 그리면 사람이 눈으로 겹쳐야 하고,
   장애 중에 그 일은 일어나지 않는다.
2. 트레이싱 검증: 주문 1건 traceId로 4개 서비스 span이 Tempo에서 연결됨(스크린샷 README).
3. 카오스 스크립트: `make chaos-kafka`, `make chaos-redis`, `make chaos-kill dispatch`. 각 실행 후 검증 SQL(주문 수 = 후보 수 + 취소 수, 라우트 stop 주문 중복 0, processed_events 중복 0)을 자동 실행.
   **감사 `UNKNOWN` 은 여기서 난다**(D3): Kafka·Redis 중단 중에 운영자 커맨드 하나를 보내면 코어의 5xx·타임아웃이
   자연히 `UNKNOWN` 을 만든다 — 인위 주입은 하지 않는다. `chaos-redis` 의 기준은 ADR-027 후속 정정의 것이다(발행이
   멈추지 않고 지연도 오르지 않는다). `STALE_PLACED` 의 재처리 경로 IT(A9)도 이 자리다.
4a. **peak-day 의 전제**(A27) — 시나리오 넷(`normal-day` · `peak-day` · `overload-day` · `cold-heavy`, 부록 A), 함대 변형
   (`peak-day` 는 80% 기준이 정하는 함대 — D2), sim-runner 이미지, `make peak`. 부록 A 의 목록과 `scenarios.yml` 의
   어긋남도 여기서 맞춘다 — **고치는 것과 함께 검사가 산출물이다**: 진실은 `scenarios.yml` 이고 부록 A 는 그것을 비추는
   표다. 시나리오 이름 집합과 핵심 파라미터(주문 수 · 냉장 비율 · 지연·실패 확률)가 같은지 보는 테스트 하나.
   D4 의 `truncated` 라벨과 설명 행의 `searchTruncated` 도 여기서 만든다 — **재기 전에 세는 칸이 있어야 한다.**
4. 피크 시나리오 `peak-day` 실행·측정: 주문 API p99, outbox 지연, 소비자 랙, 계획 시간, FAST 전환 횟수 → `docs/benchmarks/<date>-peak.md`.
   **같은 물량을 `overload-day` 로 한 번 더**(D2) — 열화 사다리 · 미배정 정책 · 계획 시간 상한의 판정 데이터. **`normal-day`
   열을 옆에 둔다.** 측정 헤더에 한 줄: **콜드 스택에서 시작했다**, 첫 계획·첫 소비 처리량은 정상 상태와 갈라 적는다(D1).
   정해진 시각의 운영자 커맨드(D3)로 `cause="manual"` 과 감사 행이 0 이 아니다. **7-0 표의 A·B 에서 닫히는 곳이 7-4 인
   행을 전부 여기서 판정한다** — 판정하지 못한 행은 이유와 함께 남긴다.
   **이 항목이 Phase 4-20 을 다시 연다** (2026-09-18 이월 — 「구성이 거리만 보는 것」).
   peak-day 는 `peak` 규모의 웨이브를 **실제 파이프라인에서** 도는 첫 자리이고, 4-20 의 판정
   기준이 「다섯 데이터셋 전부」이므로 그때의 수치가 판정의 입력이다. 남은 전환 조건은
   「`small` 에서 `savings-cw+ls` ≤ 기본 전략」이다
   ([ADR-043](adr/ADR-043-default-strategy-stays-until-peak-converges.md) ·
   [마감 리포트](benchmarks/phase4-strategies.md) §6.2).
   **`dawnline_promise_revised_total` 을 시간축으로 함께 기록하고 lag-aware grace 판정을 내린다**
   (Phase 2 「Phase 7 로 이월 (조건부)」, [ADR-020](adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md) 결정 5).
   컷오프 직후에 뭉쳐서 튀면 grace 를 컨슈머 랙에 연동하고, 흩어져 있으면 고정 90초를 유지한다.
5. 런북 RB-01~04 · 06 · 07(RB-05 는 Phase 6 에 있다 — A22). RB-06(피크 대비 체크리스트)에 **예열 항목**(D1), `docs/postmortems/2026-xx-peak-simulation.md`(가상 장애: 컷오프 시 계획 지연 → FAST 전환 → 원인·재발 방지, 실제 측정치 기반).
6. ADR 전체 확정(001–012 — 쓰이지 않은 005 · 010 · 011 · 012 를 쓴다), 사건이 지나간 재검토 지점의 소급(A28 — ADR-029 ①② · ADR-047 ④), README 완성(아키텍처 그림, 데모 GIF, 벤치마크 표, 실행 방법, 면접 스토리 링크).
   - **004 는 여기서 재확인 대상이 아니라 입력이다** — [ADR-004](adr/ADR-004-compare-against-the-boundary-not-another-solver.md) 가 Phase 4 마감에
     확정됐다(2026-09-18). 남은 것은 **한정 실행**뿐이다: 여유가 있으면 `timefold` 를 **`medium`
     한 개만** 돌린다. 조건은 §6.6 의 공정성 셋 — 같은 목적함수(§6.1) · 같은 하드 룰(§6.3) · 같은
     예산(30초) · 같은 seed 의 같은 데이터셋. **하나라도 어긋나면 리포트에 싣지 않는다**(그 수는
     알고리즘의 차이가 아니라 번역의 차이를 잰다). 결과가 결정을 바꾸는 것은 ADR-004 의 조건
     (a)~(c) 를 건드릴 때뿐이다.
   - **008(가상 스레드 + ForkJoin 분리)은 Phase 4-3 의 이월을 안고 쓴다** —
     [ADR-035](adr/ADR-035-parallel-unit-is-not-the-cluster.md) 가 병렬 단위를 이미 정정했다.
7. release.yml(GHCR 푸시, SBOM). (선택) `deploy/k8s` 매니페스트 + kind 스모크.

**DoD**
- 카오스 3종 검증 SQL 모두 0건 위반.
- 7-0 대조표의 A·B 행이 전부 판정됐다 — 닫힘 · 유지 · 다시 여는 조건과 함께 이월, 셋 중 하나로. 대조 검사가 초록이다.
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
