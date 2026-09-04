# 아키텍처 결정 기록 (ADR)

이 디렉터리는 Dawnline의 설계 결정을 기록한다. 결정 목록의 원본은 `docs/DESIGN.md` §16이며,
각 ADR은 **맥락 → 결정 → 근거 → 고려한 대안과 기각 이유 → 결과(장점·비용·되돌리는 방법)** 형식을 따른다.

원칙은 세 가지다.

1. **설계서가 먼저다.** 설계를 바꾸려면 `docs/DESIGN.md` 를 수정하고 여기에 ADR을 추가한 뒤 코드를 고친다(`CLAUDE.md`).
2. **기각한 대안을 반드시 적는다.** 무엇을 택했는지보다 무엇을 왜 버렸는지가 결정의 내용이다.
3. **비용을 숨기지 않는다.** 각 ADR의 "결과"에는 이 선택이 청구하는 대가와, 되돌리는 방법이 적혀 있다.

## 목록

| ADR | 결정 | 상태 | 문서 |
|---|---|---|---|
| 001 | Gradle 멀티모듈 모노레포 | ✅ Accepted (2026-08-29) | [ADR-001](ADR-001-gradle-multi-module-monorepo.md) |
| 002 | DB-per-service + 폴링 Outbox 릴레이 | ✅ Accepted (2026-08-29) | [ADR-002](ADR-002-db-per-service-polling-outbox.md) |
| 003 | JSON + JSON Schema 이벤트 계약 | ✅ Accepted (2026-08-29) | [ADR-003](ADR-003-json-schema-event-contracts.md) |
| 004 | 자체 휴리스틱(`sweep-greedy-nn+ls`) 기본 + Timefold 비교 | ⏳ Phase 4 예정 | — |
| 005 | Redis `SET NX` 락 + DB 낙관적 락 이중화 | ⏳ Phase 2 예정 | — |
| 006 | at-least-once + 멱등 소비자 (Kafka EOS 미사용) | ✅ Accepted (2026-08-29) | [ADR-006](ADR-006-at-least-once-idempotent-consumer.md) |
| 007 | 헥사고날 + ArchUnit 강제, 도메인/JPA 엔티티 분리 | ✅ Accepted (2026-08-29) | [ADR-007](ADR-007-hexagonal-architecture-archunit.md) |
| 008 | 가상 스레드(I/O) + ForkJoin(CPU) 분리 | ⏳ Phase 4 예정 | — |
| 009 | URL 경로 API 버저닝(v1) | ✅ Accepted (2026-09-03) | [ADR-009](ADR-009-url-path-api-versioning.md) |
| 010 | 하버사인 × 도로계수 기본, OSRM 어댑터는 선택 | ⏳ Phase 3 예정 | — |
| 011 | 롤링 배포 시 소비자 static membership | ⏳ Phase 7 예정 | — |
| 012 | CQRS 읽기 모델을 ops-api에 집중 | ⏳ Phase 6 예정 | — |
| 013 | 컨테이너 이미지 = Spring Boot Buildpacks(`bootBuildImage`) | ✅ Accepted (2026-08-29) | [ADR-013](ADR-013-container-image-buildpacks.md) |
| 014 | JDK 25 툴체인 자동 프로비저닝 (foojay-resolver) | ✅ Accepted (2026-08-29) | [ADR-014](ADR-014-jdk25-toolchain-auto-provisioning.md) |
| 015 | Outbox 발행 실패를 결정적/일시적으로 나누고 결정적 실패만 격리 | ✅ Accepted (2026-09-01) | [ADR-015](ADR-015-outbox-publish-side-quarantine.md) |
| 016 | 레디니스에서 Kafka 브로커 연결 제외 | ✅ Accepted (2026-09-01) | [ADR-016](ADR-016-readiness-excludes-kafka.md) |
| 017 | 주문 상태 머신이 순서 뒤바뀜을 흡수 (`PLANNED → DELIVERED` + 진행 단계 비교) | ✅ Accepted (2026-09-02) | [ADR-017](ADR-017-order-state-machine-absorbs-out-of-order-events.md) |
| 018 | 멱등 잠금은 Redis 키(PX 30000), DB 에는 `DONE` 만 기록 | ✅ Accepted (2026-09-03) | [ADR-018](ADR-018-idempotency-lock-in-redis-record-in-db.md) |
| 019 | 멱등 기록 보존 7일, `status` 컬럼 제거 | ✅ Accepted (2026-09-03) | [ADR-019](ADR-019-idempotency-record-retention-7-days.md) |
| 020 | 컷오프 계산은 order-service 한 곳 + 웨이브 마감 grace + 약속 개정 | ✅ Accepted (2026-09-05) | [ADR-020](ADR-020-cutoff-ownership-wave-grace-promise-revision.md) |
| 021 | 권역 시드를 order-service 지오코더의 출력에서 파생 (권역 91개) | ✅ Accepted (2026-09-05) | [ADR-021](ADR-021-zone-seed-derived-from-geocoder.md) |

- 이 표는 `docs/DESIGN.md` §16과 **같은 내용**이며 함께 갱신한다. 문서 열이 `—` 인 행은 아직 파일이 없다.
- **013·014는 §16 표에 없던 항목**으로, Phase 0 스캐폴딩 중에 확정되어 새로 추가했다.
- **015·016은 Phase 0 마감 감사에서** 드러난 결함·설계서 내부 모순을 확정한 것이다.
  015는 릴레이의 head-of-line blocking(실제 도달 가능한 결함), 016은 §8.6과 §8.4의 모순을 해소한다.
- **021은 §16 표에 없던 항목**이다. 부록 A 의 "권역 60개" 가 order-service 지오코더의 출력을 덮지
  못한다는 것을 세어 보고(91개) 알게 되어 추가했다. 덮지 못하면 그 주소의 주문이 전부
  `UNSERVICEABLE` 이 되는데, 그것이 설계된 실패 경로와 구별되지 않는다.
- **020은 Phase 2 착수 시점에** 코드보다 먼저 확정했다. §5.2 가 "Phase 2 선결" 이라고 적어 둔 항목이며,
  구현하다 마주치면 "이미 나간 약속" 을 앞에 두고 급하게 정하게 되는 종류의 결정이기 때문이다.
- **017·018은 Phase 1 구현 중에** 설계서를 코드로 옮기다 드러난 미정·모순을 확정한 것이다.
  017은 §4.5가 "상태 머신으로 흡수"라고만 적고 방법을 정하지 않은 부분, 018은 §5.1 멱등 흐름의
  1단계(DB `IN_PROGRESS`)와 3단계(`DONE` INSERT)가 서로 맞지 않던 부분이다.

## `[결정 필요]` 해소 현황 (`docs/DESIGN.md` §17)

| # | 미결 항목 | 상태 |
|---|---|---|
| 1 | 도메인 모델과 JPA 엔티티 분리 여부 | ✅ **분리한다** — [ADR-007](ADR-007-hexagonal-architecture-archunit.md) |
| 2 | 고객 API 키 적용 여부 | ✅ **도입하지 않는다** — 고객 API 무인증 (`docs/DESIGN.md` §10). 남용 방지는 레이트 리밋이 맡되, 인증이 없으므로 그 키(`customerId`)는 클라이언트 주장값이다 |
| 3 | 이미지 빌드 Jib vs Buildpacks | ✅ **Buildpacks** — [ADR-013](ADR-013-container-image-buildpacks.md) (§14 본문도 갱신됨) |
| 4 | Redis vs Valkey | ⏳ 미결 (Redis 8로 진행, 라이선스 이슈 발생 시 재검토 — 명령 호환) |
| 5 | ops-web 지도 타일 | ⏳ 미결 (Phase 6에서 결정) |
| 6 | Timefold 실험 포함 여부 | ⏳ 미결 (Phase 4 stretch, ADR-004와 함께 결정) |

`[결정 필요]` 목록 밖에서 확정된 결정도 있다 — 설계서 내부 모순을 해소한 경우다.

| 출처 | 모순 | 확정 |
|---|---|---|
| §8.6 vs §8.4 | §8.6은 레디니스에 Kafka 프로듀서 초기화를 요구했으나 §8.4는 "브로커 다운 시 주문 API 정상"을 요구 | [ADR-016](ADR-016-readiness-excludes-kafka.md) — 레디니스에서 제외 |
| §4.6 | 소비 측 DLQ만 규정하고 발행 측 실패 정책이 없어, 독약 행이 릴레이를 영구히 막을 수 있었다 | [ADR-015](ADR-015-outbox-publish-side-quarantine.md) — 결정적 실패만 격리 |

## 새 ADR을 추가할 때

- 파일명: `ADR-<번호>-<영문-kebab-슬러그>.md`. 번호는 재사용하지 않는다.
- 파일이 있는 ADR의 상태는 `Accepted` / `Superseded by ADR-xxx` / `Deprecated` 중 하나로 유지한다.
  위 목록의 `⏳ Phase N 예정` 은 상태가 아니라 **아직 파일이 없다는 표시**다 — 파일을 만들 때
  `Accepted` 로 바뀐다.
  결정을 뒤집을 때는 기존 ADR을 **수정하지 말고** 새 ADR을 쓰고, 기존 문서의 상태만 `Superseded` 로 바꾼다.
- `docs/DESIGN.md` §16 표와 이 목록을 함께 갱신한다.
- 커밋 메시지는 `docs(adr): …` (Conventional Commits, `CLAUDE.md`).
