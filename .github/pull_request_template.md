<!--
Dawnline PR 템플릿 — CLAUDE.md "PR 템플릿의 체크리스트(설계서 반영, 계약 갱신, 테스트,
메트릭, 런북)를 채운다" 를 그대로 구현한 것이다.
해당 없는 항목은 지우지 말고 `~~취소선~~` 이나 `N/A — 이유` 로 남겨라. 왜 해당 없는지가 리뷰 정보다.
-->

## 무엇을 왜 바꿨나

<!-- 3줄 이내. 배경(무슨 문제)과 접근(어떻게 풀었나). -->

- Phase / 작업 번호: <!-- 예: Phase 3 - 작업 4 (IMPLEMENTATION_PLAN.md) -->
- 관련 설계 절: <!-- 예: DESIGN.md §6.3, §5.3 -->
- 관련 ADR: <!-- 예: ADR-004 / 없음 -->

## 변경 유형

- [ ] feat  - [ ] fix  - [ ] refactor  - [ ] perf  - [ ] test  - [ ] docs  - [ ] chore
- [ ] 이벤트 계약 변경 포함 (`contracts/events/**`)
- [ ] DB 스키마 변경 포함 (Flyway 마이그레이션)
- [ ] 설정/인프라 변경 포함 (`deploy/**`, `gradle/libs.versions.toml`)

## 체크리스트

### 1. 설계서 반영
- [ ] 설계서(`docs/DESIGN.md`)와 일치한다. 설계에 없는 서비스·토픽·테이블을 추가하지 않았다.
- [ ] 설계를 바꿔야 했다면 **DESIGN.md 를 먼저 고치고** `docs/adr/` 에 ADR 을 추가했다.
- [ ] `[결정 필요]` 항목을 추측으로 채우지 않았다 (미해결이면 아래 "미해결"에 적었다).

### 2. 계약 갱신
- [ ] 새 이벤트/필드는 `contracts/events/*.schema.json` 과 `examples/` 를 먼저 갱신했다.
- [ ] 같은 major 안에서는 **필드 추가만** 했다 (필수 필드 추가·삭제·타입 변경 없음, DESIGN.md §4.7).
- [ ] 스키마 계약 테스트를 추가/갱신했다 (발행자·소비자 양쪽).
- [ ] REST API 를 바꿨다면 `contracts/openapi/*.yaml` 을 재생성해 커밋했다.

### 3. 테스트
- [ ] `./gradlew build` 통과 (컴파일 + 단위 + ArchUnit + 계약 + JaCoCo 게이트).
- [ ] `./gradlew integrationTest` 통과 (Testcontainers 가 필요한 변경인 경우).
- [ ] 단위 테스트로 새 분기·상태 전이를 덮었다. optimizer 패키지는 라인 커버리지 ≥ 85%.
- [ ] 테스트를 약화(어설션 삭제, `@Disabled`)시켜 통과시키지 않았다.
- [ ] 결정론: seed 고정·`Clock` 주입으로 같은 입력이면 같은 결과가 나온다.

### 4. 메트릭·관측성
- [ ] 새 유스케이스/실패 경로에 메트릭을 추가했다 (`libs/observability` 의 이름 상수 사용, DESIGN.md §9.1).
- [ ] 로그는 구조화 JSON 이고 MDC(`orderId/waveId/routeId/eventId`)를 채운다.
- [ ] 전체 주소·고객 식별 정보를 로그에 남기지 않는다 (DESIGN.md §9.3).
- [ ] 대시보드/알림 규칙 갱신이 필요하면 `deploy/compose/grafana|prometheus` 에 반영했다.

### 5. 런북·문서
- [ ] 새로운 실패 모드가 생겼다면 `docs/runbooks/RB-0x.md` 를 추가/갱신했다.
- [ ] 운영 절차(수동 개입, DLQ replay 등)가 바뀌었다면 런북에 반영했다.
- [ ] README / 벤치마크 문서 갱신이 필요하면 반영했다.

### 6. 아키텍처 불변 규칙 (CLAUDE.md)
- [ ] 상태 변경과 이벤트 발행이 같은 트랜잭션의 `outbox_events` 기록이다 (유스케이스에서 `KafkaTemplate` 직접 호출 없음).
- [ ] 모든 Kafka 리스너가 `IdempotentConsumer`(`processed_events`) 를 통과한다.
- [ ] 자기 서비스 DB 만 접근한다. 코어 서비스 간 동기 호출 없음.
- [ ] `domain` 패키지에 Spring/JPA import 가 없다 (`dispatch-service`의 `domain.optimizer` 는 순수 Java).
- [ ] 상태 전이는 애그리거트 메서드로만 한다 (세터로 status 변경 없음).
- [ ] Redis 키가 사라져도 DB 만으로 정확성이 유지되는 폴백 경로가 있다 (DESIGN.md §7.2).
- [ ] 금액은 정수 KRW(long), 좌표는 `NUMERIC(9,6)`/double, 시간은 `TIMESTAMPTZ`/`Instant`.
- [ ] ID 는 `Ids.newId()` (UUIDv7) 로 애플리케이션에서 생성한다.
- [ ] 시간·난수를 `Clock`/`RandomGenerator` 로 주입받는다.

### 7. 데이터·성능
- [ ] **인덱스를 추가했다면** `EXPLAIN (ANALYZE, BUFFERS)` 결과를 아래에 첨부하고 설계서에 반영했다 (CLAUDE.md 불변규칙 11).
- [ ] Flyway 마이그레이션은 앞으로만 간다(기존 스크립트 수정 없음). `ddl-auto=validate` 로 검증된다.
- [ ] 최적화 결과가 "좋아졌다"고 주장한다면 **벤치마크 수치**를 아래에 붙였다.

### 8. 보안·위생
- [ ] `.env`·시크릿·개인정보 샘플을 커밋하지 않았다 (`.env.example` 만).
- [ ] 새 의존성을 추가했다면 `gradle/libs.versions.toml` 에 고정하고 커밋 메시지에 이유를 적었다.
- [ ] 커밋이 Conventional Commits 형식이고, 한 커밋이 한 관심사다.

## 실행한 검증 명령과 결과

<!-- "통과할 것 같다" 금지. 실제 출력 요약을 붙인다. -->

```text
$ ./gradlew build
...

$ ./gradlew integrationTest
...
```

## 수치 (해당 시)

<!-- 벤치마크 비교표, k6 p50/p95/p99·오류율, 계획 시간, EXPLAIN 결과 등 -->

| 항목 | 이전 | 이후 | 비고 |
|---|---|---|---|
|  |  |  |  |

## 미해결 · 후속 작업

<!-- 알려진 제약, 다음 PR 로 미룬 것, 사용자 확인이 필요한 [결정 필요] 항목 -->

## 롤백 방법

<!-- 되돌릴 때 주의할 점(마이그레이션, 이벤트 계약, 소비자 오프셋 등). 단순 revert 면 "revert 로 충분". -->
