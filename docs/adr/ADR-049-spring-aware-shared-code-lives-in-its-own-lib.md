# ADR-049 — Spring 을 아는 공유 코드는 자기 lib 에 산다, common 은 순수하게 남는다

| 항목 | 내용 |
|---|---|
| 상태 | Accepted |
| 결정일 | 2026-09-23 |
| 관련 문서 | `docs/DESIGN.md` §12 · §13 · `CLAUDE.md` 불변규칙 5 |
| 관련 ADR | [ADR-007](ADR-007-hexagonal-architecture-archunit.md) (경계는 ArchUnit 이 지킨다) · [ADR-044](ADR-044-endpoints-are-few-enough-to-see-all.md) (같은 것이 여러 자리에 있으면 대조를 둔다) |

---

## 맥락

`ProblemDetailsAdvice` 가 세 서비스(order · tracking · dispatch)에 **거의 글자 그대로** 있다.
정규화해서 diff 를 뜨면 갈라지는 칸은 **하나**다 — `RETRY_AFTER_SECONDS`. 나머지 180 줄은
같다: `type` URI 접두어, `Retry-After` 규칙, 프레임워크가 만든 `ProblemDetail`(type 이
`about:blank` 가 아니라 **`null`** 이다)의 분류, 검증 실패의 필드 목록, 마지막 방어선.

셋째가 생겼을 때 이미 뽑았어야 했고, **넷째가 곧 온다** — Phase 6 의 ops-api 다. 그것이 이
뽑기의 실제 수요다: 사본이 하나 더 늘기 전에 기준을 만든다.

뽑는 것은 정해졌는데 **어디에 두는가**가 열려 있었다. `libs/common` 의 `build.gradle.kts` 는
첫 줄에 「프레임워크 비의존 순수 Java 다 (CLAUDE.md 불변규칙 5)」라고 적고 있고, Spring 은
`test`·`testFixtures` 스코프에만 있다. `ResponseEntityExceptionHandler` 를 `main` 에 들이면
그 문장이 거짓이 된다.

## 결정

### 1. Spring 을 아는 공유 코드는 자기 `libs/*` 모듈에 산다 — `libs/web` 을 만든다

`libs/common` 은 값 객체·ID·에러 **모델**이고 프레임워크를 모른다. `libs/messaging` 과
`libs/observability` 는 Spring 을 안다. **저장소에 이미 그 패턴이 있다** — Spring 을 아는 web
조각의 자리는 그 둘 옆이지 `common` 안이 아니다.

**근거: 관측(재현됨)** — 후보였던 Gradle 피처 변형(`registerFeature("web")`, `libs/common` 안의
별도 소스셋)은 같은 일을 하는 **두 번째 길**을 여는 것이고, 그 비용이 설정에서 바로 드러났다.
이 저장소의 가드 둘이 피처 변형을 모른다:

- `buildSrc/dawnline.java-conventions` 의 `check` → `compileIntegrationTestJava` 의존
  (CLAUDE.md 「새 소스셋을 만들면 `check` 의 컴파일 의존에 연결한다」). 새 소스셋은 손으로 건다.
- JaCoCo `classDirectories` 는 `main` 만 본다. 손대지 않으면 **뽑은 코드가 커버리지 게이트에서
  조용히 사라진다.**

모듈은 둘 다 공짜로 받는다. 가드가 모르는 구조를 들이면 「조용히 사라지는」 자리가 하나 늘고,
그것을 막으려고 훅을 짜고 있다는 것 자체가 신호다.

### 2. `libs/common` 의 `main` 은 `org.springframework` 를 참조하지 않는다 — ArchUnit 이 강제한다

결정 1 은 **규칙**이고, 규칙은 적어 두는 것만으로 다음 사람에게 닿지 않는다.
`libs/common/build.gradle.kts` 의 첫 줄 선언은 **문장이지 강제가 아니다** — Spring 의존을 한 줄
추가하면 그 주석은 그대로 있고 빌드는 통과한다.

`LibsCommonIsFrameworkFreeTest` 가 `libs/common` 의 **main 출력만** 읽어
`org.springframework..`·`jakarta.persistence..` 의존이 없음을 본다. 분석 대상이 main 뿐이라는
것은 전제라서 **첫 어설션이 그것을 스스로 말한다** — 읽은 클래스가 0 이면 규칙은 아무것도
검사하지 않으면서 통과한다(「폴백 테스트는 전제를 첫 어설션으로 말한다」와 같은 축).

### 3. 갈라지는 칸 하나는 **추상 메서드**로 둔다 — 기본값을 주지 않는다

`ProblemDetailsAdviceSupport.retryAfterSeconds()` 는 `abstract` 다. `Map.of()` 를 기본값으로
주면 tracking·dispatch 는 아무것도 쓰지 않게 되고, **「이 서비스에는 그런 오류가 없다」는
판단이 코드에서 사라진다.** 지금 그 자리에는 왜 비었는지가 서비스마다 다르게 적혀 있다 —
tracking 은 「404 는 재시도가 통하지만 *언제*인지를 우리가 모른다(브로커 랙)」, dispatch 는
「계획 실행은 `wave_id` UNIQUE 로 멱등이다」. 불변규칙 11 의 「넣지 않기로 한 판단도 기록한다」와
같은 축이고, 추상 메서드는 그 기록을 **강제**한다.

### 4. 「`@RestControllerAdvice` 를 가진 서비스는 전부 이 기반을 쓴다」를 ArchUnit 에 건다

ArchUnit 규칙 9. **빼는 방식**이다 — 서비스 이름을 열거하지 않고 `@ControllerAdvice` 계열
어노테이션이 붙은 클래스 **전부**에 대해 `ProblemDetailsAdviceSupport` 하위인지 묻는다. 네
번째 서비스(ops-api)는 자동으로 대상이 된다.

**근거: 관측(재현됨)** — 열거였던 규칙이 새 구성원을 놓친 일이 이 저장소에 있었다. ADR-009 의
버전 규칙은 order-service 만 지키고 dispatch 의 컨트롤러 셋은 리터럴 `/api/v1` 로 들어왔고
(2026-09-19), 그 사실을 **아무 검사도 보고 있지 않았다**(§13 규칙 8 이 그래서 생겼다).

규칙이 가리키는 타입은 문자열 FQN 이다 — `libs/common` 의 testFixtures 가 `libs/web` 을
컴파일 의존으로 가질 수 없기 때문이다(방향이 반대다). 문자열 링크는 **끊어져도 조용하다**:
클래스 이름이 바뀌면 규칙은 아무것도 매치하지 않으면서 통과한다. 그래서 `libs/web` 의 테스트가
그 문자열과 `ProblemDetailsAdviceSupport.class.getName()` 을 **대조한다**(§13 규칙 3).

## 기각한 것

| 안 | 기각 이유 |
|---|---|
| `libs/common` 의 Gradle 피처 변형 | 결정 1 의 근거. 가드 둘(`check` 컴파일 의존 · JaCoCo `classDirectories`)이 모르는 구조이고, Spring 인식 공유 코드를 격리하는 **두 번째 길**이 된다 |
| `libs/common` 의 `main` 에 그냥 넣기 | 불변규칙 5 의 근거가 도메인에만 있는 것이 아니다 — `tools/benchmark` 가 Spring 없이 `libs/common` 을 쓴다 |
| 뽑지 않고 사본 셋 유지 | 「두 번째가 기준」을 이미 지났고, 네 번째(ops-api)가 Phase 6 에 있다. 사본은 *갈라질 때* 비용을 내는데 그 순간에는 어느 쪽이 옳은지 알 수 없다 |
| 서비스마다 `@RestControllerAdvice` 없이 기반 클래스를 빈으로 등록 | 서비스마다 `RETRY_AFTER_SECONDS` 가 다르므로 하위 클래스가 필요하다. 그리고 하위 클래스가 있으면 규칙 9 가 「기반을 쓰는가」를 물을 대상이 생긴다 |
| 기반 클래스를 `libs/observability` 에 얹기 | 그 모듈은 메트릭·MDC·로그 설정이다. 오류 **응답의 모양**은 관측이 아니라 web 계약이고, 섞으면 「어디에 무엇이 있는가」가 이름으로 답해지지 않는다 |

## 결과

- 새 모듈 `libs/web` (`com.dawnline.web`). `libs/common` 에 `api` 로 의존하고
  `spring-boot-starter-web` 을 `api` 로 노출한다.
- `docs/DESIGN.md` §12 의 모듈 목록과 `CLAUDE.md` 「저장소 구조」에 `web` 한 줄. **설계 변경이
  아니라 사실의 갱신**이다 — 결정은 이 ADR 이 적는다.
- ArchUnit 규칙이 8 개에서 **10 개**가 된다(9: 오류 응답 기반, 10: `libs/common` main 순수).
  §13 의 규칙 목록과 매핑표를 함께 갱신한다.

## 재검토 지점

1. **`libs/web` 에 둘째 주민이 생길 때.** 지금은 `ProblemDetailsAdviceSupport` 하나뿐이라
   「모듈 하나에 클래스 하나」다. 다음 후보는 서비스 셋에 같은 모양으로 있는 것들이다 —
   `ApiVersionConfigurer`([ADR-009](ADR-009-url-path-api-versioning.md))·`OpenApiConfig` 의
   공통 뼈대. 둘째가 올 때 **모듈이 맞았는지**가 판정된다: 오지 않으면 이 모듈은 클래스 하나를
   위한 경계이고, 그때는 규칙(결정 1)이 값을 하고 있는지 다시 묻는다.
2. **ops-api 가 네 번째가 될 때** (Phase 6). 규칙 9 가 열거가 아니라 조건이라는 것은
   **그때 처음 증명된다** — 지금은 셋 다 이미 기반을 쓰도록 고친 상태라 규칙이 잡을 것이 없다.
   ops-api 의 advice 를 기반 없이 먼저 써 보고 규칙이 빨간지 확인한 뒤 고치는 것이 그 증명이다.
