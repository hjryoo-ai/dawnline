# ADR-013 — 컨테이너 이미지 빌드에 Spring Boot Buildpacks(`bootBuildImage`) 사용

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-08-29 |
| 관련 문서 | `docs/DESIGN.md` §14(CI/CD와 배포) |
| 해소한 미결 | `docs/DESIGN.md` §17 **[결정 필요] (3) 이미지 빌드 Jib vs Buildpacks** → **Buildpacks** |
| 구현 위치 | `buildSrc/src/main/kotlin/dawnline.spring-service.gradle.kts` |

---

## 맥락

컨테이너 이미지가 필요한 곳이 셋이다.

1. 로컬 전체 스택(`make up`) — Compose가 서비스 5개를 이미지로 띄운다.
2. CI 스모크(§14) — 빌드한 이미지로 Compose를 올려 주문 20건 E2E를 돌린다.
3. 릴리스(`release.yml`) — 태그 시 GHCR 푸시 + SBOM.

즉 **서비스 5개 × 세 경로**가 같은 이미지 규약을 공유해야 한다. 설계서 §14는 이 선택을 `[결정 필요]`로 남겨 두었다.
`CLAUDE.md` 는 "새 라이브러리 추가는 최소화"를 요구한다.

## 결정

Spring Boot Gradle 플러그인이 기본 제공하는 **`bootBuildImage`(Cloud Native Buildpacks / Paketo)** 를 사용한다.

- 컨벤션 플러그인 `dawnline.spring-service` 에서 한 번만 설정하고 서비스 5개가 상속한다.
  - `imageName = dawnline/<module>:<version>`
  - `environment = { BP_JVM_VERSION = <libs.versions.toml 의 java 버전> }` — 버전 카탈로그가 유일한 진실 원천이므로
    JDK 버전을 올리면 런타임 이미지도 따라 올라간다.
- **저장소에 `Dockerfile` 을 두지 않는다.**

## 근거

- **추가 빌드 플러그인 의존성이 0이다.** `bootBuildImage` 는 이미 적용 중인 `org.springframework.boot` 플러그인의
  태스크다. Jib은 플러그인을 하나 더 들이고 그 플러그인의 Boot 4 / JDK 25 호환성을 우리가 책임져야 한다.
- **Boot의 계층화 규약을 공짜로 얻는다.** Buildpacks는 Boot의 layertools 규약
  (`dependencies` / `spring-boot-loader` / `snapshot-dependencies` / `application`)에 맞춰 이미지 레이어를 나눈다.
  코드만 고친 재빌드는 마지막 얇은 레이어만 바뀌므로 Compose 재기동과 GHCR 푸시가 빨라진다.
- **JVM 컨테이너 튜닝을 위임한다.** 메모리 계산기, 컨테이너 인지 힙 설정, 비루트 사용자, CDS 등
  우리가 직접 하면 계속 틀리는 것들을 Paketo가 관리한다. 이 프로젝트의 차별점은 이미지 최적화가 아니다.
- **버전 상승 경로가 한 줄이다.** `BP_JVM_VERSION` 을 카탈로그에서 읽으므로 ADR-014의 툴체인 버전과 항상 일치한다.
- **컨벤션 일관성.** 새 서비스를 추가하면 이미지 빌드가 자동으로 따라온다. 사람이 잊을 수 있는 단계가 없다.

## 고려한 대안과 기각 이유

**(a) Jib (`com.google.cloud.tools.jib`)**

- 장점은 분명하다. **Docker 데몬이 필요 없고**(레지스트리로 직접 푸시), 빌드가 빠르며, 이미지가 작다.
  데몬 없는 CI 러너에서 특히 강하다.
- 기각 이유:
  - 빌드 플러그인 의존성이 추가된다(`CLAUDE.md` 의존성 최소화 원칙).
  - Boot 4 / JDK 25 조합에 맞는 베이스 이미지 지원을 **우리가 검증해야 한다.** 2026-08-29 시점에 검증하지 않았고,
    "호환된다고 가정하지 않는다"가 이 저장소의 규칙이다. 검증 비용이 이득보다 크다고 판단했다.
  - 베이스 이미지 선택·보안 패치 추적이 우리 몫이 된다. Buildpacks는 런 이미지 갱신으로 재빌드만 하면 된다.
- **재검토 조건**(이 결정을 다시 열어야 할 신호): CI의 이미지 빌드 시간이 파이프라인의 병목이 되거나,
  Docker 데몬을 쓸 수 없는 러너로 옮기게 되는 경우. 그때 Jib의 Boot 4 지원을 실제로 빌드해 확인한 뒤 판단한다.

**(b) 서비스별 수동 `Dockerfile`**

- 장점: 완전한 제어와 투명성. 무엇이 이미지에 들어가는지 한눈에 보인다.
- 기각: 5개 서비스의 Dockerfile을 손으로 유지해야 한다 — 베이스 이미지 패치, 비루트 사용자, 레이어 분할,
  JVM 플래그, `.dockerignore`. 5배의 유지보수를 지불하는데, 우리가 특별히 제어하고 싶은 것이 없다.
  (Buildpacks가 감당 못 할 요구가 생기면 그때 서비스 하나만 Dockerfile로 내려올 수 있다.)

**(c) 이미지를 만들지 않고 `bootJar` 를 JRE 이미지에 볼륨 마운트**

- 장점: 로컬 반복이 가장 빠르다.
- 기각: 로컬 실행 산출물과 CI·릴리스 산출물이 달라진다. "로컬에서 되는데 CI에서 안 된다"의 전형적 원인이며,
  §14의 CI 스모크가 검증하려는 대상 자체가 흐려진다.

## 결과

**장점**

- 유지보수할 이미지 정의 파일이 0개. 서비스 추가 시 이미지 설정 작업이 없다.
- 레이어 캐시로 재빌드·푸시가 가볍다.
- JDK 버전이 툴체인(ADR-014)과 이미지에서 자동으로 일치한다.

**비용**

- **Docker 데몬이 필요하다.** 이미지 빌드는 로컬/CI 모두 데몬을 전제로 한다. README 전제조건에 명시한다.
  (일반 `./gradlew build` 는 데몬이 필요 없다. 필요한 것은 `bootBuildImage` 와 Testcontainers 통합 테스트다.)
- **첫 빌드가 느리다.** 빌더 이미지와 런 이미지를 내려받으므로 수백 MB의 최초 pull이 발생한다. 이후는 캐시된다.
- **이미지 크기가 크다.** distroless 계열 대비 베이스가 두껍다. 로컬 데모 환경에서는 문제가 아니라고 판단했다.
- **빌더 이미지 버전이 우리 통제 밖이다.** 기본 빌더는 시간이 지나면 갱신되므로 완전한 재현성이 없다.
  재현성이 필요해지면 `builder` 를 특정 태그로 고정한다 — 지금은 최신 보안 패치를 자동으로 받는 쪽을 택했다.
- **오프라인 빌드 불가.** 폐쇄망에서는 빌더·런 이미지를 사전에 캐시해야 한다.

**되돌리는 방법**

`bootJar` 산출물은 어느 방식에서도 동일하므로 전환 비용은 낮다. `dawnline.spring-service.gradle.kts` 의
`bootBuildImage` 블록을 지우고 Jib 플러그인을 적용하거나 `Dockerfile` 을 추가하면 된다.
**파일 하나의 변경**으로 서비스 5개가 함께 이동한다 — 컨벤션 플러그인으로 묶어 둔 이유가 이것이다.

## 참조

- `docs/DESIGN.md` §14(CI/CD와 배포), §17 [결정 필요] (3) — 이 ADR로 해소됨
- `buildSrc/src/main/kotlin/dawnline.spring-service.gradle.kts`
- 관련 ADR: ADR-001(컨벤션 플러그인), ADR-014(JDK 버전의 단일 진실 원천)
