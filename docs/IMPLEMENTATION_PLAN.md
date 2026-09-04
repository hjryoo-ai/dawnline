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
1. 시드: FC 3, 캠프 10, 권역 60(geohash5), 재고 스텁, 차량·기사는 Phase 3에서. 시드는 Flyway `R__seed_*.sql` 또는 `sim-runner seed` 명령 `[둘 중 하나로 통일]`.
2. Redis GEO 적재(기동 시), `GEOSEARCH` 기반 최근접 FC 선택, geohash5 → zone 캐시.
3. FC 선택 규칙(§5.2 1~6단계), `UNSERVICEABLE` 경로.
4. `Wave` 애그리거트·상태 머신, 편입 로직(UNIQUE + FOR UPDATE 짧은 트랜잭션), 컷오프 스케줄러(30초, Redis 락, Lua 언락), `CLOSING→CLOSED` 전이와 `wave.closed` outbox.
5. 리스너: `order.placed` → 계획·편입·`fulfillment.planned` 발행. `order.cancelled` → 웨이브에서 제거(OPEN일 때만).
6. 순서 역전 처리: `order.cancelled`가 먼저 오면 취소 마커 저장 후 `order.placed` 도착 시 무시.
7. 테스트: 스케줄러 인스턴스 2개 동시 실행 시 `wave.closed` 정확히 1회(통합), 컷오프 이후 주문이 다음 웨이브로 가는지, GEO 폴백(Redis 중단).

**DoD**
- `make demo` 실행 시 주문 200건이 자동으로 웨이브에 편입되고, 컷오프(테스트용 짧은 컷오프 설정)에 `wave.closed`가 캠프별 1회 발행됨을 Kafka 소비 로그·DB로 확인.
- 이중 마감 없음 테스트 통과.

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
