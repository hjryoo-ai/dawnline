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
| 009 | URL 경로 API 버저닝(v1) | ⏳ Phase 1 예정 | — |
| 010 | 하버사인 × 도로계수 기본, OSRM 어댑터는 선택 | ⏳ Phase 3 예정 | — |
| 011 | 롤링 배포 시 소비자 static membership | ⏳ Phase 7 예정 | — |
| 012 | CQRS 읽기 모델을 ops-api에 집중 | ⏳ Phase 6 예정 | — |
| 013 | 컨테이너 이미지 = Spring Boot Buildpacks(`bootBuildImage`) | ✅ Accepted (2026-08-29) | [ADR-013](ADR-013-container-image-buildpacks.md) |
| 014 | JDK 25 툴체인 자동 프로비저닝 (foojay-resolver) | ✅ Accepted (2026-08-29) | [ADR-014](ADR-014-jdk25-toolchain-auto-provisioning.md) |

- 001–012는 `docs/DESIGN.md` §16의 목록과 번호가 일치한다.
- **013·014는 §16 표에 없던 항목**으로, Phase 0 스캐폴딩 중에 확정되어 새로 추가했다.
  둘 다 `docs/DESIGN.md` §17의 `[결정 필요]` 또는 §14의 미결 항목을 해소한다.

## `[결정 필요]` 해소 현황 (`docs/DESIGN.md` §17)

| # | 미결 항목 | 상태 |
|---|---|---|
| 1 | 도메인 모델과 JPA 엔티티 분리 여부 | ✅ **분리한다** — [ADR-007](ADR-007-hexagonal-architecture-archunit.md) |
| 2 | 고객 API 키 적용 여부 | ⏳ 미결 (Phase 1에서 결정) |
| 3 | 이미지 빌드 Jib vs Buildpacks | ✅ **Buildpacks** — [ADR-013](ADR-013-container-image-buildpacks.md) |
| 4 | Redis vs Valkey | ⏳ 미결 (Redis 8로 진행, 라이선스 이슈 발생 시 재검토 — 명령 호환) |
| 5 | ops-web 지도 타일 | ⏳ 미결 (Phase 6에서 결정) |
| 6 | Timefold 실험 포함 여부 | ⏳ 미결 (Phase 4 stretch, ADR-004와 함께 결정) |

## 새 ADR을 추가할 때

- 파일명: `ADR-<번호>-<영문-kebab-슬러그>.md`. 번호는 재사용하지 않는다.
- 상태는 `Accepted` / `Superseded by ADR-xxx` / `Deprecated` 중 하나로 유지한다.
  결정을 뒤집을 때는 기존 ADR을 **수정하지 말고** 새 ADR을 쓰고, 기존 문서의 상태만 `Superseded` 로 바꾼다.
- `docs/DESIGN.md` §16 표와 이 목록을 함께 갱신한다.
- 커밋 메시지는 `docs(adr): …` (Conventional Commits, `CLAUDE.md`).
