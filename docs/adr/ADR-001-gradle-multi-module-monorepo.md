# ADR-001 — Gradle 멀티모듈 모노레포

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-08-29 |
| 관련 문서 | `docs/DESIGN.md` §12, §13, §14 · `CLAUDE.md` 저장소 구조 |
| 관련 ADR | ADR-003(이벤트 계약), ADR-007(헥사고날 경계), ADR-013(이미지 빌드) |

---

## 맥락

Dawnline 은 코어 서비스 5개(order / fulfillment / dispatch / tracking / ops-api), 공유 라이브러리 3개(`libs/common`,
`libs/messaging`, `libs/observability`), 도구 2개(`tools/sim-runner`, `tools/benchmark`), 프론트 1개(`apps/ops-web`)로
구성된다. 이 시스템의 결합점은 두 가지다.

1. **이벤트 계약**(`contracts/events/*.schema.json`). 하나의 필드 추가가 발행자 1개 + 소비자 2~3개를 동시에 건드린다.
2. **공통 규약**(컴파일러 플래그 `-Werror`, JaCoCo 게이트, `integrationTest` 소스셋, Boot BOM 고정).
   5개 서비스가 같은 규약을 지켜야 ArchUnit·커버리지 게이트가 의미를 가진다.

개발 인원은 1명이고, 이 저장소의 1차 독자는 채용 리뷰어다. "클론 → 한 명령 빌드 → 한 명령 기동"이 되지 않으면
설계의 질과 무관하게 평가에 실패한다.

## 결정

**단일 Git 저장소 + Gradle 멀티프로젝트(Kotlin DSL)** 를 채택한다.

- `settings.gradle.kts` 가 모든 모듈을 `include` 한다. 모듈 경로가 곧 저장소 구조다(`docs/DESIGN.md` §12).
- 공통 규약은 `buildSrc` 의 컨벤션 플러그인 2개로만 정의한다.
  `dawnline.java-conventions`(툴체인·컴파일러 플래그·소스셋·JaCoCo), `dawnline.spring-service`(Boot 플러그인·actuator·이미지).
  각 모듈의 `build.gradle.kts` 는 컨벤션 적용 + 자기 의존성 선언만 한다.
- 라이브러리 버전은 `gradle/libs.versions.toml` **한 곳**에서만 고정하고, Spring Boot BOM 이 관리하는 것은 여기에 쓰지 않는다.
- 모듈 의존 규칙: `services/*` 는 `libs/*` 에만 의존한다. 서비스 간 소스 의존은 금지(Gradle 의존성 + ArchUnit 이중 강제).
  `tools/benchmark` 는 `services/dispatch-service` 의 순수 도메인만 의존한다(ADR-007).

## 근거

- **계약 변경의 원자성**: 스키마·발행자·소비자·계약 테스트가 한 커밋·한 PR에 들어간다. 저장소가 나뉘면
  같은 변경이 N개 PR로 쪼개지고, 그 사이 기간에 저장소들의 조합이 깨진 상태로 존재한다.
- **단일 검증 명령**: `./gradlew build` 하나로 전 모듈의 컴파일·단위·ArchUnit·계약 테스트·커버리지 게이트가 돈다.
  CI(§14)와 로컬이 같은 명령을 쓴다.
- **규약 중복 제거**: `-Werror`, 툴체인, `integrationTest` 소스셋을 5곳에 복붙하면 반드시 어긋난다.
  `buildSrc` 는 이 규약을 컴파일되는 코드로 만든다.
- **리뷰어 온보딩**: 디렉터리 하나만 열면 전체 시스템의 지도가 보인다. 포트폴리오에서 이건 기능 요구사항에 가깝다.
- **증분 빌드**: Gradle 설정 캐시·빌드 캐시·병렬 실행(`gradle.properties`)으로 모노레포의 빌드 시간 문제를 상쇄한다.

## 고려한 대안과 기각 이유

| 대안 | 장점 | 기각 이유 |
|---|---|---|
| 서비스별 저장소(polyrepo) | 배포 단위 = 저장소 단위, 팀별 소유권이 명확 | 계약 변경의 원자성 상실. 로컬 개발에 저장소 N개 클론 + 공유 라이브러리 사내 아티팩트 게시 파이프라인 필요. 1인 프로젝트에서 이 비용은 순손실이고, 리뷰어가 전체를 보려면 저장소 8개를 열어야 한다 |
| 단일 Gradle 프로젝트(모듈 분리 없음) | 가장 단순 | 모듈 경계가 없으면 서비스 간 클래스 참조를 컴파일러가 막지 못한다. "MSA를 설계했다"는 주장을 코드가 배신한다 |
| Maven 멀티모듈 | 익숙함, 안정성 | 버전 카탈로그·설정 캐시·컨벤션 플러그인에 해당하는 것이 없거나 약하다. 소스셋 분리(`integrationTest`)에 플러그인 추가가 필요 |
| Bazel / Pants | 대규모 모노레포의 정확한 증분 빌드 | 학습·유지 비용이 프로젝트 전체 규모에 맞먹는다. 얻는 이점(원격 캐시, 정밀 증분)이 이 규모에서 체감되지 않는다 |

## 결과

**장점**

- 이벤트 계약과 그 소비자를 한 번에 바꾸고 한 번에 검증할 수 있다(ADR-003의 계약 테스트가 실효를 가진다).
- 버전이 한 곳에 있어 "어느 서비스가 어떤 Boot 를 쓰는가"라는 질문 자체가 사라진다.
- 새 서비스 추가 = `settings.gradle.kts` 한 줄 + 6줄짜리 `build.gradle.kts`.

**비용**

- 저장소가 커질수록 전체 빌드 시간이 늘어난다. 대응: 병렬·빌드 캐시 활성화(`gradle.properties`), CI 에서
  변경 모듈 기준 태스크 선택은 필요해지면 도입한다(현재는 전체 빌드가 수용 가능한 시간).
- 모듈 경계는 Gradle 의존성 선언으로만 강제되므로, 실수로 서비스 간 의존을 추가할 수 있다.
  → ArchUnit 규칙 3(다른 `com.dawnline.<svc>` 패키지 참조 금지, §13)으로 이중 방어한다.
- 배포 단위와 저장소 단위가 다르다. 태그 하나가 서비스 5개 이미지를 의미한다.
  "변경된 서비스만 릴리스"는 지금 하지 않는다(§14 `release.yml` 후속 과제).

**되돌리는 방법**

각 `services/*` 는 `libs/*` 에만 의존하므로 결합이 얕다. 분리가 필요해지면 `git subtree split` 으로 서비스를 떼어내고
`libs/*` 를 사내 아티팩트로 게시하면 된다. 저장소 분리를 막는 코드 결합(서비스 간 클래스 참조)은 설계상 존재하지 않는다.
이 "되돌릴 수 있음"이 모노레포를 안전하게 만드는 전제이며, ADR-007의 경계 강제가 그 전제를 지킨다.

## 참조

- `docs/DESIGN.md` §12(저장소 구조), §13(테스트 전략·ArchUnit 규칙), §14(CI/CD)
- `settings.gradle.kts`, `gradle/libs.versions.toml`, `buildSrc/src/main/kotlin/dawnline.java-conventions.gradle.kts`
- `CLAUDE.md` — "새 라이브러리 추가는 최소화", "버전은 `libs.versions.toml` 에서만 고정"
