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
| 24시간 넘은 `order.placed` → `STALE_PLACED` | ⚠️ **단위만** | `FcSelectionTest` 5건(경계 양쪽 1초, 상한이 설정값인 것, FC 선택보다 먼저 판정) · `PlanOrderServiceTest.하루_넘은_컷오프는_STALE_PLACED_다`. 브로커를 지나는 IT 는 없다 — 판정이 순수 함수 안에 있고 시각은 주입된 `Clock` 이라 IT 가 더 볼 것이 없다고 봤다. **DLQ replay 경로가 생기는 Phase 7 에서 다시 본다** |
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

8. **릴레이 리더 락 + ADR** (순서 무관): 서비스당 릴레이 단일 활성을 보장한다(Redis `SET NX` +
   주기 갱신, 락 상실 시 발행 중단). 스케일아웃으로 인스턴스가 2개 이상이 되기 **전에** 들어가야
   한다 — 그 전까지는 인스턴스 1개라는 사실이 §4.4 의 전제를 충족시키고 있을 뿐이다.

**테스트** (각 단계에 붙는다): 룰 평가기 단위(각 룰 위반/통과), 하드 룰 위반 라우트가 최종 산출에
없음(`PlanValidator`), **seed 고정 결정론**(같은 seed → 같은 결과, 불변규칙 12), 5,000 주문 통합
계획(시간 측정), `wave.closed` 중복 도착 멱등.

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
8. **`small` 레짐 격차 — 조건부 항목** (Phase 3-4 게이트 결정의 후속). 1번의 국소 탐색이 들어간
   뒤 `small` 에서 `sweep-greedy-nn+ls` 가 `baseline-nn` 을 따라잡는지 다시 잰다
   (2026-09-05 기준 격차 **+8.8%**, 분해는 `docs/benchmarks/phase3-baseline.md` §4-5).
   - **닫히면**: 항목 종료. 수치를 그 문서에 추가하고 README 의 「알려진 레짐」 문단을 갱신한다.
   - **닫히지 않으면**: **클러스터 수를 비용 결정으로 만든다.** 최소 실행 가능 수
     `⌈수요/용량⌉` 에서 시작해, **분할이 비용을 줄일 때만** 클러스터를 늘린다. 그때 ADR 을 쓴다.

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

9. **테스트 격리 — 시드를 고치는 IT 는 자기 픽스처 행을 만든다.** 지금 `DispatchAdminIT` 는
   시드(룰·차량·기사)를 직접 고치고 `@AfterEach` 로 되돌린다. 되돌리기는 **병렬 실행이 들어오면
   무너지는 격리**다(같은 시드 행을 두 테스트가 동시에 본다). 캠프 하나를 이 테스트만의 것으로
   만들어 그 아래에 차량·기사·룰을 넣는 쪽으로 옮긴다. 지금 당장 깨지지 않는 이유는 실행이
   순차이기 때문일 뿐이고, 그 사실은 테스트 어디에도 적혀 있지 않다.

10. **미배정 선택 규칙 — §6.5 3단계 정정** (2026-09-05 결정). 3단계의 "그래도 없으면 미배정" 은
    **어느 주문을 남길지를 말하지 않는다.** 말하지 않으면 아무도 안 정한 것이 아니라 *우연이
    정한다* — 지금은 마지막 클러스터에 남은 주문이 그대로 떨어진다. 측정이 그것을 드러냈다:
    `small` 에서 두 전략의 미배정 건수가 **9 로 같은데 페널티는 20,000원 다르다.** 건수가 같고
    값이 다르면 남긴 대상이 다르다는 뜻이고, 그 차이를 만든 것은 알고리즘이 아니라 **부재하는 규칙**이다.

    규칙은 목적함수를 그대로 따른다 — **`UNASSIGNED_PENALTY` 가 싼 것(우선순위가 낮은 것)부터
    뺀다.** 값싼 그리디이고 최적이 아니지만 최적일 필요가 없다: 여기서 재는 것은 "용량이 모자랄 때
    누가 남는가" 이고, 그 답이 **재현 가능하다는 것**이 지금 없는 성질이다. `GreedyAssigner` 안에서
    끝나므로 파이프라인 형태는 바뀌지 않는다. 테스트는 **용량을 일부러 모자라게 만든 문제**에서
    남는 주문이 우선순위 오름차순인지 본다.

    **주의**: 5a 에 적어 둔 대로 `fulfillment.planned` 에 `priority` 가 없어 운영 경로의 후보는
    전부 우선순위 0 이다. 그러면 이 규칙은 벤치마크에서만 구별을 만들고 운영에서는 임의 순서로
    돌아간다 — 우선도의 출처를 정하는 **계약 변경**이 함께 가야 이 항목이 끝난다.

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

---

## Phase 6 — 백오피스 (ops-api + ops-web)

> **선결 — `rm_orders` 는 약속을 두 개 든다.** §8.1 의 정시율은 *원 약속* 기준인데
> order-service 의 `promised_start/end` 는 개정 경로에서 덮인다(ADR-020 결정 3 — 덮는 것이 맞다).
> 원 약속을 아는 곳은 `order.placed` 이벤트뿐이고, 두 기준을 모두 낼 수 있는 곳은 이 읽기 모델이다.
> §5.5 DDL 의 `promised_end` 한 칸으로는 `dawnline_delivery_on_time_ratio{basis}` 를 낼 수 없고,
> 그러면 **개정으로 정시율을 세탁할 수 있게 된다** — 두 값으로 내기로 한 이유가 바로 그것이었다.
> Phase 2-7 에서 order-service 쪽을 구현하며 드러났다.

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
