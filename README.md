# Dawnline

**당일·새벽 배송 오더 오케스트레이션 & 디스패치 플랫폼** — 주문 접수부터 기사 경로 배정까지를
룰 엔진과 비용 기반 경로 최적화로 푸는, 이벤트 드리븐 MSA 포트폴리오 프로젝트.

> ### 현재 상태: **Phase 0 (스캐폴딩) 진행 중**
>
> 이 README는 **골격**이다. 아직 구현되지 않은 것을 구현된 것처럼 쓰지 않는다.
> Phase 0의 범위는 **빌드**와 **로컬 인프라 스택 기동**, 그리고 헬스 체크만 노출하는 **빈 서비스 5개**까지다.
> 비즈니스 로직은 Phase 1부터 들어간다.
> 벤치마크 수치·정시율·데모 화면은 측정하거나 만든 뒤에 채운다. 자리표시자에는 **어느 Phase에서 채우는지**를 적었다.
> 진행 상황은 [현재 구현 현황](#현재-구현-현황)을 참고.

---

## 무엇을 푸는가

새벽·당일 배송은 "주문을 받는 일"이 아니라 **마감 시각까지 물량을 경로로 바꾸는 일**이다.
자정 컷오프에 캠프 하나로 수천 건이 몰리고, 그 전부를 수십 대의 차량에 나눠 담아 순서를 정해야 한다.
그것도 냉장 물량은 냉장 차량에만, 기사 근무시간 안에, 약속한 배송창을 지키면서, 가능한 한 싸게.

이 프로젝트가 증명하려는 것은 두 가지다.

1. **룰 기반 최소 비용 배송 알고리즘** — 하드/소프트 룰 엔진 + 비용 모델 + 휴리스틱 파이프라인.
   그리고 "왜 이 주문이 이 차량에 갔는가 / 왜 미배정인가"를 운영자가 조회할 수 있는 **설명 가능성**.
2. **성수기에도 무너지지 않는 이벤트 드리븐 MSA** — Outbox와 멱등 소비자로 유실·중복을 구조적으로 막고,
   계획이 느려지면 품질을 낮춰서라도 마감을 지키는 **명시적 열화(degrade) 경로**.

### 도메인 요약

| 용어 | 정의 |
|---|---|
| **FC** | 재고를 보관·피킹하는 물류센터. 여러 캠프에 물량을 공급 |
| **Camp** | 라스트마일 출발 거점. 차량·기사가 소속됨 |
| **Zone** | 캠프 하위 배송 구역 (geohash 5자리 prefix 집합) |
| **Wave** | (캠프, 티어, 컷오프) 단위로 묶인 주문 집합. **계획의 단위** |
| **Route** | 차량·기사 1회 출발의 배송 계획. 순서 있는 Stop 목록 |
| **Stop** | 하나의 배송지 방문. 같은 주소의 여러 주문은 하나의 Stop으로 통합 |
| **Plan** | 웨이브 하나에 대한 최적화 실행 1회와 그 결과(라우트·미배정·비용·설명) |

서비스 티어는 `DAWN`(새벽) / `SAME_DAY`(당일) / `NEXT_DAY`(익일)이며 티어마다 컷오프와 약속 배송창이 다르다.
자세한 정의는 [DESIGN.md §2](docs/DESIGN.md)를 참고.

---

## 아키텍처 개요

### 엔드투엔드 흐름

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

### 설계의 뼈대

- **서비스 간 쓰기 경로는 이벤트만.** 코어 서비스끼리 동기 REST 호출을 하지 않는다.
  필요한 데이터는 이벤트 페이로드 스냅샷이거나 자기 DB 프로젝션이다. 동기 조회는 `ops-api` → 코어 방향만 허용한다.
- **DB-per-service.** 서비스는 자기 DB만 본다. 크로스 서비스 JOIN·FK는 금지다.
- **Transactional Outbox.** 도메인 상태 변경과 이벤트 발행이 같은 DB 트랜잭션에 들어간다.
  Kafka는 이 트랜잭션에 참여할 수 없으므로(→ [ADR-006](docs/adr/ADR-006-at-least-once-idempotent-consumer.md)),
  원자성을 PostgreSQL 하나로 환원한다.
- **at-least-once + 멱등 소비자.** 중복은 제거 대상이 아니라 흡수 대상이다.
  모든 리스너가 `processed_events(event_id, consumer)` 를 비즈니스 트랜잭션과 같은 트랜잭션에서 기록한다.
- **헥사고날 + ArchUnit.** 경계는 문서가 아니라 테스트로 강제한다. 특히 `dispatch-service` 의 `domain.optimizer` 는
  Spring에 의존하지 않는 순수 Java여서, 벤치마크 도구가 서비스와 **똑같은 코드**를 실행한다.

각 결정의 근거와 기각한 대안은 [docs/adr/](docs/adr/README.md)에 있다.

---

## 핵심: 디스패치 최적화 엔진

웨이브 하나에 대해, 캠프의 차량 집합과 후보 주문 집합이 주어졌을 때 **총비용을 최소화하는 라우트 집합**을 찾는다.

```
cost(R) = Σ_r [ fixed(v) + dist_km·perKm(v) + dur_min·perMin(v) + Σ_stop late_min·penaltyPerMin ]
        + Σ_{미배정} unassignedPenalty(o)
        + Σ 소프트 룰 페널티
```

시간창이 있는 용량 제약 차량 경로 문제(CVRPTW)의 변형이며 NP-hard다. 따라서 "최적"을
**주어진 시간 예산 안에서 베이스라인 대비 검증된 개선**으로 정의하고, 벤치마크로 증명한다.

파이프라인: `Stop 통합 → Sweep 클러스터링 → Greedy 차량 할당 → Nearest-Neighbor 시퀀싱 → Local Search 개선 → 하드 룰 재검증`.
각 단계는 교체 가능한 클래스이고, 전략(`baseline-nn`, `sweep-greedy-nn`, `sweep-greedy-nn+ls`, `savings-cw+ls`)은
플러그인으로 등록된다. 룰은 코드가 아니라 **DB의 데이터**이며, 하드 룰 위반과 소프트 룰 페널티는 모두
`Explanation` 으로 남아 운영자가 조회할 수 있다.

**벤치마크 — Phase 3 최초 측정** ([`docs/benchmarks/phase3-baseline.md`](docs/benchmarks/phase3-baseline.md), seed 고정 5회 중앙값):

| 데이터셋 | `baseline-nn` | `sweep-greedy-nn` | 차이 |
|---|---:|---:|---|
| small (500주문 / 5대) | **1,510,366** | 1,642,762 | +8.8% (**진다**) |
| medium (2,000 / 20) | 5,032,092 | **4,581,241** | −9.0% |
| large (5,000 / 40) | 14,005,048 | **13,610,957** | −2.8% |

> **알려진 레짐 — small 에서는 기본 전략이 베이스라인보다 나쁘다.** 차량이 4~5대면 "누구를 어느
> 차에 태울지" 의 자유도가 사실상 없어(최소 필요 차량 4대) 클러스터링이 값을 만들 자리가 없고,
> 다섯째 차의 고정비 45,000원이 순수한 비용이 된다. 격차 132,396원의 98%가 그 차 한 대와 그것이
> 끄는 거리·시간이다.
>
> **지는 표에도 이기는 항이 있다**: 같은 실행에서 스윕은 **지각 stop 을 14 → 5 로 줄이고** 소프트
> 페널티를 17,851원 번다 — 클러스터가 시간창을 더 잘 지킨다. 그 이득이 차 한 대 값을 못 넘을
> 뿐이다. 분해와 메커니즘은 [phase3-baseline.md §4-5](docs/benchmarks/phase3-baseline.md) 에 있고,
> CI 회귀 게이트는 그 자유도가 처음 생기는 `medium` 에서 돈다.

> 목표치(`baseline-nn` 대비 총비용 ≥ 15% 절감, 5,000 주문 계획 p95 ≤ 30초)는 **Phase 4 의 목표**이며,
> 미달 시 원인 분석과 함께 실제 수치를 기록한다.

상세: [DESIGN.md §6](docs/DESIGN.md)

---

## 기술 스택

애플리케이션 버전은 `gradle/libs.versions.toml`, 인프라 이미지 버전은 `deploy/compose/.env` **한 곳에서만** 고정한다.
아래 표는 2026-08-29 기준으로 실제 확인한 버전이다.

| 계층 | 선택 | 버전 |
|---|---|---|
| 언어·런타임 | Java (Eclipse Temurin) | **25 LTS** (25.0.4.1, Gradle이 자동 프로비저닝 → [ADR-014](docs/adr/ADR-014-jdk25-toolchain-auto-provisioning.md)) |
| 빌드 | Gradle (Kotlin DSL) | 9.3.0 wrapper, 설정 캐시·병렬 빌드 ON |
| 프레임워크 | Spring Boot | **4.1.1** (Spring Framework 7.0.9, Spring Kafka 4.1.1, Spring Security 7.1.1) |
| ORM·마이그레이션 | Hibernate ORM 7.4.5 / Flyway 12.4.0 | Boot BOM 관리, `ddl-auto=validate` |
| 직렬화 | Jackson **3** (`tools.jackson.*`) | Boot 4의 기본. Boot 3의 `com.fasterxml.jackson.*` 이 아니다 |
| 메시징 | Apache Kafka (KRaft) | `apache/kafka:4.3.1` |
| RDB | PostgreSQL | `postgres:18.2` — 서비스별 DB |
| 캐시·조정 | Redis | `redis:8.8.2` — GEO·Lua·NX 락 |
| 관측성 | Micrometer 1.17.1 + OpenTelemetry 1.62.0 | `prom/prometheus:v3.14.0`, `grafana/grafana:13.1.0`, `grafana/tempo:2.9.5`, `otel/opentelemetry-collector-contrib:0.159.0` |
| 테스트 | JUnit Jupiter 6.0.3, AssertJ, Testcontainers 2.0.5, ArchUnit 1.5.0, k6 | `integrationTest` 소스셋 분리 |
| 컨테이너 이미지 | Spring Boot Buildpacks (`bootBuildImage`) | → [ADR-013](docs/adr/ADR-013-container-image-buildpacks.md) |
| 프론트 (계획) | React 19 + Vite + TypeScript + Leaflet | Phase 6 |

컴파일은 `-parameters -Xlint:all,-serial,-processing,-this-escape -Werror` 로 돈다. **경고 하나도 허용하지 않는다.**

---

## 저장소 구조

```
dawnline/
├── docs/
│   ├── DESIGN.md                 # 진실의 원천 (설계서)
│   ├── IMPLEMENTATION_PLAN.md    # Phase별 작업 지시와 DoD
│   ├── adr/                      # 아키텍처 결정 기록
│   ├── benchmarks/               # 전략 비교·피크 측정 리포트 (Phase 3~)
│   ├── runbooks/                 # 운영 런북 (Phase 7)
│   └── postmortems/              # 피크 시뮬레이션 포스트모템 (Phase 7)
├── contracts/
│   ├── events/                   # 이벤트 JSON Schema + examples
│   └── openapi/                  # 서비스별 OpenAPI (빌드 산출물 커밋)
├── libs/
│   ├── common/                   # UUIDv7, GeoPoint, Geohash, Money(KRW), TimeWindow, 도메인 예외
│   ├── messaging/                # EventEnvelope, Outbox 릴레이, IdempotentConsumer, Kafka 설정
│   └── observability/            # 메트릭 명명, MDC 필터, JSON 로그, OTel 설정
├── services/
│   ├── order-service/            # 주문 접수·취소·멱등
│   ├── fulfillment-service/      # FC 선택, 캠프/권역, 웨이브·컷오프
│   ├── dispatch-service/         # 룰 엔진 + 최적화 + 라우트  ← domain/optimizer 가 핵심
│   ├── tracking-service/         # 배송 진행·ETA·지연 위험
│   └── ops-api/                  # CQRS 읽기 모델, 운영자 커맨드
├── apps/ops-web/                 # 운영 콘솔 (React, Phase 6)
├── tools/{sim-runner,benchmark}/ # 부하·기사 시뮬레이터 / 전략 벤치마크 하네스
├── deploy/{compose,k8s}/         # 로컬 전체 스택 / (선택) 매니페스트
├── buildSrc/                     # dawnline.java-conventions, dawnline.spring-service
└── gradle/libs.versions.toml     # 애플리케이션 의존성 버전의 유일한 고정 지점
```

---

## 빠른 시작

**전제조건**

- Git
- Docker (Compose v2) — 로컬 스택, Testcontainers 통합 테스트, 이미지 빌드에 필요
- **JDK 설치는 필요 없다.** Gradle wrapper가 Temurin 25를 자동으로 내려받는다
  ([ADR-014](docs/adr/ADR-014-jdk25-toolchain-auto-provisioning.md)).
  폐쇄망이라 자동 다운로드가 불가능하면 JDK 25를 설치한 뒤
  `org.gradle.java.installations.paths=<경로>` 를 `gradle.properties` 에 지정한다.

```bash
git clone <repo> && cd dawnline

./gradlew build          # 컴파일 + 단위 + ArchUnit + 계약 테스트 + 커버리지 게이트
./gradlew integrationTest # Testcontainers 통합 테스트 (Docker 필요)

make up                  # 로컬 전체 스택 기동 (PostgreSQL · Kafka · Redis · 관측성 · 서비스 5개)
make down                # 종료
```

**Phase 0 완료 시 확인할 수 있는 것** (= Phase 0의 DoD)

- `./gradlew build` 통과, ArchUnit 테스트 존재·통과
- 서비스 5개의 `/actuator/health/readiness` 가 200
- Kafka 토픽 목록에 [DESIGN.md §4.1](docs/DESIGN.md)의 토픽 전체가 존재
- `libs/messaging` 통합 테스트: outbox INSERT → 릴레이 → Kafka 수신 → 같은 이벤트 2회 전달 시 1회만 처리

**아직 동작하지 않는 것** — `make demo`(주문 시드 + smoke 시나리오), `make peak`, `make chaos-*` 는
각각 Phase 1–2, Phase 7에서 의미를 갖는다. Phase 0의 서비스는 헬스 체크만 노출하는 빈 껍데기다.

---

## 현재 구현 현황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 모노레포·컨벤션 플러그인, `libs/*`, Compose 스택, ArchUnit, CI 골격, 이벤트 계약, ADR | ✅ 완료 |
| 1 | `order-service` — 주문 접수·취소, 멱등 POST, Outbox 발행, k6 부하 측정 | ✅ 완료 |
| 2 | `fulfillment-service` — FC 선택, 권역, 웨이브·컷오프 스케줄러(분산 락) | ✅ 완료 |
| **3** | `dispatch-service` 코어 — 룰 엔진, 비용 모델, `sweep-greedy-nn`, 설명, 벤치마크 하네스 | ✅ 완료 |
| 4 | 최적화 고도화 — Local Search, `savings-cw+ls`, FAST 열화 모드, 전략 비교 리포트 | 🔨 다음 |
| 5 | `tracking-service` + 기사 시뮬레이터 + 지연 위험 감지·부분 재계획 | ⬜ 예정 |
| 6 | 백오피스 — `ops-api` 읽기 모델·커맨드, `ops-web` 대시보드·라우트 지도 | ⬜ 예정 |
| 7 | 신뢰성·관측성 마감 — Grafana 대시보드, 카오스 스크립트, 피크 측정, 런북·포스트모템 | ⬜ 예정 |

Phase 3까지가 데모 가능한 MVP다. 각 Phase의 작업 목록과 완료 기준(DoD)은
[IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)에 있다.

### 측정해서 채울 자리

이 프로젝트는 "측정하지 않은 것은 주장하지 않는다"를 규칙으로 삼는다. 아래는 아직 **비어 있는** 항목이다.

| 항목 | 채우는 시점 |
|---|---|
| ~~주문 API p50/p95/p99, 오류율 (k6, 500 rps)~~ | ✅ [phase1-orders-k6.md](docs/benchmarks/phase1-orders-k6.md) (2026-09-05) |
| ~~전략별 총비용·계획 시간 비교표~~ | ✅ [phase3-baseline.md](docs/benchmarks/phase3-baseline.md) (2026-09-05). Phase 4 에서 `+ls`·`savings-cw+ls` 가 붙는다 |
| 피크 시나리오 실측 vs SLO 표 | Phase 7 → `docs/benchmarks/` |
| 카오스 검증 결과 (Kafka/Redis 중단, 인스턴스 강제 종료) | Phase 7 |
| 아키텍처 다이어그램 이미지, 데모 GIF, Tempo 트레이스 스크린샷 | Phase 7 |
| 정시 배송률 (지연 주입 시뮬레이션) | Phase 5 이후 측정, Phase 7 리포트 |

---

## 문서

| 문서 | 내용 |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | 설계서. 이 저장소의 진실의 원천. 도메인·이벤트·서비스·최적화 엔진·SLO |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | Phase별 작업 지시와 완료 기준(DoD) |
| [docs/adr/](docs/adr/README.md) | 아키텍처 결정 기록 — 무엇을 왜 택했고 무엇을 왜 버렸는가 |
| [CLAUDE.md](CLAUDE.md) | 이 저장소의 불변 규칙 12개 (아키텍처·코딩 컨벤션·작업 방식) |

**먼저 읽으면 좋은 ADR**

- [ADR-006 — at-least-once + 멱등 소비자](docs/adr/ADR-006-at-least-once-idempotent-consumer.md):
  Kafka의 "exactly-once"로는 왜 DB 쓰기와의 원자성을 얻을 수 없는지.
- [ADR-007 — 헥사고날 + 도메인/JPA 분리](docs/adr/ADR-007-hexagonal-architecture-archunit.md):
  매퍼 보일러플레이트라는 실제 비용을 지불하고 무엇을 사는지.
- [ADR-002 — DB-per-service + 폴링 Outbox](docs/adr/ADR-002-db-per-service-polling-outbox.md):
  Debezium CDC를 왜 지금은 쓰지 않는지, 그리고 어떻게 CDC로 가는 길을 막지 않았는지.
