# CLAUDE.md — Dawnline (당일·새벽 배송 디스패치 플랫폼, 포트폴리오)

이 파일은 Claude Code가 이 저장소에서 작업할 때 항상 따르는 규칙이다.
전체 설계는 `docs/DESIGN.md`, 작업 순서는 `docs/IMPLEMENTATION_PLAN.md`를 따른다.
설계서와 코드가 충돌하면 설계서가 우선이다. 설계 변경이 필요하면 **먼저** `docs/DESIGN.md`를 수정하고 `docs/adr/`에 ADR을 추가한 뒤 코드를 바꾼다.
`[결정 필요]` 항목은 구현 전에 사용자에게 묻는다. 추측으로 채우지 않는다.

## 프로젝트 한 줄 요약

주문 접수 → FC/캠프/권역 결정 → 컷오프 웨이브 → **룰 엔진 + 비용 기반 경로 최적화** → 라우트 배정 → 배송 추적·재계획.
이벤트 드리븐 MSA(Kafka + Outbox), 서비스별 PostgreSQL, Redis(GEO·락·멱등), 운영자 백오피스, 피크·장애 시뮬레이션.

## 기술 스택 (버전은 `gradle/libs.versions.toml`, `deploy/compose/.env`에서만 고정)

- Java 25 LTS (Temurin), Gradle 9.x Kotlin DSL, 멀티모듈 모노레포
- Spring Boot 4.1.x (Spring Framework 7, Spring Kafka 4.1, Spring Security 7.1, Hibernate ORM 7 — BOM 관리)
- Apache Kafka 4.3.x (KRaft), PostgreSQL 18, Redis 8.x, Flyway
- 테스트: JUnit(BOM), AssertJ, Testcontainers, ArchUnit, WireMock, k6
- 관측성: Micrometer + OpenTelemetry → Prometheus / Grafana / Tempo
- 프론트(ops-web): React 19 + Vite + TypeScript + Leaflet
- 새 라이브러리 추가는 최소화. 추가 시 이 파일과 `libs.versions.toml`을 함께 갱신하고 커밋 메시지에 이유를 쓴다.
- Spring Boot 4 호환 여부가 불확실한 라이브러리(springdoc, Resilience4j 등)는 **먼저 빌드로 확인**하고, 안 되면 대체안을 제시한다. 호환된다고 가정하지 않는다.

## 저장소 구조

```
libs/{common,messaging,observability}
services/{order-service,fulfillment-service,dispatch-service,tracking-service,ops-api}
apps/ops-web
tools/{sim-runner,benchmark}
contracts/{events,openapi}
deploy/{compose,k8s}
docs/{DESIGN.md,IMPLEMENTATION_PLAN.md,adr,runbooks,benchmarks,postmortems}
```

서비스 패키지는 `com.dawnline.<service>` 아래 `domain / application(port.in, port.out) / adapter(in.web, in.messaging, out.persistence, out.messaging, out.redis) / config`.

## 명령어

```bash
./gradlew build                      # 컴파일 + 단위 + ArchUnit + 계약 테스트 + JaCoCo 게이트
./gradlew integrationTest            # Testcontainers 통합 테스트 (Docker 필요)
./gradlew :services:dispatch-service:test --tests '*Optimizer*'
./gradlew :tools:benchmark:run --args='--dataset small --strategies baseline-nn,sweep-greedy-nn+ls'
make up          # 전체 스택 기동 (Compose)
make demo        # 시드 + smoke 시나리오 + Grafana/Swagger URL 출력
make peak        # 피크 시나리오
make chaos-kafka # Kafka 중단→복구 검증 스크립트
make down
```

작업을 "완료"라고 말하기 전에 반드시 `./gradlew build`가 통과해야 하고, 해당 Phase의 DoD 검증 명령을 실제로 실행해 결과를 보고한다.

## 아키텍처 불변 규칙 (ArchUnit으로도 강제됨)

1. **Outbox 필수**: 도메인 상태 변경과 이벤트 발행은 같은 DB 트랜잭션에서 `outbox_events`에 기록한다. `KafkaTemplate`을 유스케이스에서 직접 호출하지 않는다.
2. **멱등 소비자 필수**: 모든 Kafka 리스너는 `processed_events(event_id, consumer)` 체크를 트랜잭션 안에서 먼저 한다. `libs/messaging`의 `IdempotentConsumer`를 사용한다.
3. **서비스 간 DB 접근 금지**: 자기 서비스 DB만 접근. 다른 서비스 테이블 JOIN·FK 금지.
4. **코어 서비스 간 동기 호출 금지**: 필요한 데이터는 이벤트 페이로드 스냅샷 또는 자기 DB 프로젝션. 동기 REST는 ops-api → 코어 방향만.
5. **domain 패키지는 프레임워크 비의존**: Spring, JPA import 금지. 특히 `dispatch-service`의 `domain.optimizer`는 순수 Java여야 벤치마크 도구에서 그대로 실행된다.
6. **상태 전이는 상태 머신 메서드로만**: `order.markDispatched()`처럼 애그리거트 메서드로 전이하고, 잘못된 전이는 도메인 예외. 세터로 status를 바꾸지 않는다.
7. **Redis는 진실 저장소가 아님**: 어떤 키가 사라져도 DB만으로 정확성이 유지되도록 폴백 경로를 만든다(`docs/DESIGN.md` §7.2 표).
8. **이벤트 계약 우선**: 새 이벤트/필드는 `contracts/events/*.schema.json`과 `examples/`를 먼저 갱신하고 계약 테스트를 추가한다. 같은 major 안에서는 필드 추가만.
9. **돈은 정수 KRW**, 좌표는 `NUMERIC(9,6)`/`double`, 시간은 `TIMESTAMPTZ`/`Instant`. 부동소수 금액 금지.
10. **ID는 UUIDv7**을 애플리케이션에서 생성한다(`libs/common`의 `Ids.newId()`).
11. **인덱스 추가 금지(설계서 명시분 외)**: 필요하면 EXPLAIN 결과를 PR 설명에 첨부하고 설계서에 반영.
12. **시간과 난수는 주입**: `Clock`, `RandomGenerator`(seed)를 생성자로 받는다. 최적화 결과는 seed가 같으면 동일해야 한다.

## 코딩 컨벤션

- 값 객체·이벤트 페이로드·명령은 `record`. 분기 가능한 타입은 `sealed interface` + 패턴 매칭.
- 널 가능성은 JSpecify 어노테이션(`@Nullable`)으로 명시. `Optional`은 반환 타입에만.
- 로그: 구조화 JSON, MDC에 `orderId/waveId/routeId/eventId`. 전체 주소·고객 식별 정보는 로그 금지.
- 예외: 도메인 예외(`DomainException` 하위) → HTTP 매핑은 `adapter.in.web`의 단일 `@ControllerAdvice`. 응답은 RFC 9457 Problem Details.
- 테스트 이름: `메서드_상황_기대결과` 한국어 가능. 통합 테스트는 `*IT.java`, `integrationTest` 소스셋.
- 커밋: Conventional Commits (`feat(dispatch): …`, `test(order): …`, `docs(adr): …`). 한 커밋은 한 관심사.
- PR 템플릿의 체크리스트(설계서 반영, 계약 갱신, 테스트, 메트릭, 런북)를 채운다.

## 작업 방식

- `docs/IMPLEMENTATION_PLAN.md`의 Phase 순서를 지킨다. Phase 내부에서는 작업 순서를 제안하고 사용자 확인 후 진행한다.
- 큰 작업 전에는 먼저 **변경 계획**(파일 목록, 스키마 변경, 이벤트 변경)을 짧게 제시하고 동의를 받는다.
- 코드 생성 후에는 반드시 빌드·테스트를 실행하고 실제 출력을 근거로 보고한다. "통과할 것" 같은 추측 보고 금지.
- 완료 보고 형식: (1) 무엇을 만들었나 (2) 실행한 검증 명령과 결과 (3) 설계서와 달라진 점·ADR 필요 여부 (4) 다음 단계 제안.
- 자신 없는 라이브러리 API·버전은 소스/문서를 확인한 뒤 쓴다. 특히 Spring Boot 4/Spring Framework 7에서 바뀐 설정 키·패키지(예: Jackson 3, 모듈화된 스타터)는 릴리스 노트로 확인한다.

## 하지 말 것

- 설계서에 없는 서비스·토픽·테이블을 임의로 추가하지 않는다.
- `docker compose down -v` 같은 데이터 삭제 명령을 사용자 확인 없이 실행하지 않는다.
- `.env`, 시크릿, 개인정보 샘플을 커밋하지 않는다(`.env.example`만).
- 테스트를 통과시키기 위해 테스트를 약화(어설션 삭제, `@Disabled`)하지 않는다. 실패 원인을 고친다.
- 최적화 결과를 "더 좋아졌다"고 말할 때는 반드시 벤치마크 수치를 함께 보고한다.
